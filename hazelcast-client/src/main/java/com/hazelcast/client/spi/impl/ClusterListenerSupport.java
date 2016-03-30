/*
 * Copyright (c) 2008-2016, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.client.spi.impl;

import com.hazelcast.client.AuthenticationException;
import com.hazelcast.client.config.ClientNetworkConfig;
import com.hazelcast.client.connection.AddressProvider;
import com.hazelcast.client.connection.ClientConnectionManager;
import com.hazelcast.client.impl.HazelcastClientInstanceImpl;
import com.hazelcast.client.impl.LifecycleServiceImpl;
import com.hazelcast.client.impl.client.ClientPrincipal;
import com.hazelcast.client.spi.ClientClusterService;
import com.hazelcast.client.spi.ClientExecutionService;
import com.hazelcast.core.LifecycleEvent;
import com.hazelcast.core.Member;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;
import com.hazelcast.nio.Address;
import com.hazelcast.nio.Connection;
import com.hazelcast.nio.ConnectionListener;
import com.hazelcast.spi.exception.TargetDisconnectedException;
import com.hazelcast.util.Clock;
import com.hazelcast.util.executor.PoolExecutorThreadFactory;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import static com.hazelcast.client.config.ClientProperty.SHUFFLE_MEMBER_LIST;

public abstract class ClusterListenerSupport implements ConnectionListener, ConnectionHeartbeatListener, ClientClusterService {

    private static final ILogger LOGGER = Logger.getLogger(ClusterListenerSupport.class);
    private static final long TERMINATE_TIMEOUT_SECONDS = 30;

    protected final HazelcastClientInstanceImpl client;
    private final Collection<AddressProvider> addressProviders;
    private final ExecutorService clusterExecutor;
    private final boolean shuffleMemberList;

    private ClientConnectionManager connectionManager;
    private ClientMembershipListener clientMembershipListener;
    private volatile Address ownerConnectionAddress;
    private volatile ClientPrincipal principal;

    public ClusterListenerSupport(HazelcastClientInstanceImpl client, Collection<AddressProvider> addressProviders) {
        this.client = client;
        this.addressProviders = addressProviders;
        this.shuffleMemberList = client.getClientProperties().getBoolean(SHUFFLE_MEMBER_LIST);
        this.clusterExecutor = createSingleThreadExecutorService(client);
    }

    private ExecutorService createSingleThreadExecutorService(HazelcastClientInstanceImpl client) {
        ThreadGroup threadGroup = client.getThreadGroup();
        ClassLoader classLoader = client.getClientConfig().getClassLoader();
        PoolExecutorThreadFactory threadFactory =
                new PoolExecutorThreadFactory(threadGroup, client.getName() + ".cluster-", classLoader);
        return Executors.newSingleThreadExecutor(threadFactory);
    }

    protected void init() {
        this.connectionManager = client.getConnectionManager();
        this.clientMembershipListener = new ClientMembershipListener(client);
        connectionManager.addConnectionListener(this);
        connectionManager.addConnectionHeartbeatListener(this);
    }

    public Address getOwnerConnectionAddress() {
        return ownerConnectionAddress;
    }

    public void shutdown() {
        clusterExecutor.shutdown();
        try {
            boolean success = clusterExecutor.awaitTermination(TERMINATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!success) {
                LOGGER.warning("cluster executor awaitTermination could not completed in "
                        + TERMINATE_TIMEOUT_SECONDS + " seconds");
            }
        } catch (InterruptedException e) {
            LOGGER.warning("cluster executor await termination is interrupted", e);
        }
    }

    private Collection<InetSocketAddress> getSocketAddresses() {
        final List<InetSocketAddress> socketAddresses = new LinkedList<InetSocketAddress>();

        Collection<Member> memberList = getMemberList();
        for (Member member : memberList) {
            socketAddresses.add(member.getSocketAddress());
        }

        for (AddressProvider addressProvider : addressProviders) {
            socketAddresses.addAll(addressProvider.loadAddresses());
        }

        if (shuffleMemberList) {
            Collections.shuffle(socketAddresses);
        }

        return socketAddresses;
    }

    public ClientPrincipal getPrincipal() {
        return principal;
    }

    public void setPrincipal(ClientPrincipal principal) {
        this.principal = principal;
    }

    public void connectToCluster() throws Exception {
        ownerConnectionAddress = null;

        final ClientNetworkConfig networkConfig = client.getClientConfig().getNetworkConfig();
        final int connAttemptLimit = networkConfig.getConnectionAttemptLimit();
        final int connectionAttemptPeriod = networkConfig.getConnectionAttemptPeriod();

        final int connectionAttemptLimit = connAttemptLimit == 0 ? Integer.MAX_VALUE : connAttemptLimit;

        int attempt = 0;
        Set<InetSocketAddress> triedAddresses = new HashSet<InetSocketAddress>();
        while (attempt < connectionAttemptLimit) {
            if (!client.getLifecycleService().isRunning()) {
                if (LOGGER.isFinestEnabled()) {
                    LOGGER.finest("Giving up on retrying to connect to cluster since client is shutdown");
                }
                break;
            }
            attempt++;
            final long nextTry = Clock.currentTimeMillis() + connectionAttemptPeriod;

            boolean isConnected = connect(triedAddresses);

            if (isConnected) {
                return;
            }

            final long remainingTime = nextTry - Clock.currentTimeMillis();
            LOGGER.warning(
                    String.format("Unable to get alive cluster connection, try in %d ms later, attempt %d of %d.",
                            Math.max(0, remainingTime), attempt, connectionAttemptLimit));

            if (remainingTime > 0) {
                try {
                    Thread.sleep(remainingTime);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        throw new IllegalStateException("Unable to connect to any address in the config! "
                + "The following addresses were tried:" + triedAddresses);
    }

    private boolean connect(Set<InetSocketAddress> triedAddresses) throws Exception {
        final Collection<InetSocketAddress> socketAddresses = getSocketAddresses();
        for (InetSocketAddress inetSocketAddress : socketAddresses) {
            if (!client.getLifecycleService().isRunning()) {
                if (LOGGER.isFinestEnabled()) {
                    LOGGER.finest("Giving up on retrying to connect to cluster since client is shutdown");
                }
                break;
            }
            try {
                triedAddresses.add(inetSocketAddress);
                Address address = new Address(inetSocketAddress);
                if (LOGGER.isFinestEnabled()) {
                    LOGGER.finest("Trying to connect to " + address);
                }
                Connection connection = connectionManager.getOrConnect(address, true);
                ownerConnectionAddress = connection.getEndPoint();
                clientMembershipListener.listenMembershipEvents(ownerConnectionAddress);
                fireConnectionEvent(LifecycleEvent.LifecycleState.CLIENT_CONNECTED);
                return true;
            } catch (Exception e) {
                Level level = e instanceof AuthenticationException ? Level.WARNING : Level.FINEST;
                LOGGER.log(level, "Exception during initial connection to " + inetSocketAddress, e);
            }
        }
        return false;
    }

    private void fireConnectionEvent(final LifecycleEvent.LifecycleState state) {
        ClientExecutionService executionService = client.getClientExecutionService();
        executionService.execute(new Runnable() {
            @Override
            public void run() {
                final LifecycleServiceImpl lifecycleService = (LifecycleServiceImpl) client.getLifecycleService();
                lifecycleService.fireLifecycleEvent(state);
            }
        });
    }

    @Override
    public void connectionAdded(Connection connection) {
    }

    @Override
    public void connectionRemoved(Connection connection) {
        if (connection.getEndPoint().equals(ownerConnectionAddress)) {
            if (client.getLifecycleService().isRunning()) {
                clusterExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            fireConnectionEvent(LifecycleEvent.LifecycleState.CLIENT_DISCONNECTED);
                            connectToCluster();
                        } catch (Exception e) {
                            LOGGER.warning("Could not re-connect to cluster shutting down the client", e);
                            client.getLifecycleService().shutdown();
                        }
                    }
                });
            }
        }
    }

    @Override
    public void heartBeatStarted(Connection connection) {
    }

    @Override
    public void heartBeatStopped(Connection connection) {
        if (connection.getEndPoint().equals(ownerConnectionAddress)) {
            connectionManager.destroyConnection(connection, new TargetDisconnectedException("Heartbeat stopped"));
        }
    }
}
