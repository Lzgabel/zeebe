/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.broker.clustering.atomix;

import io.atomix.cluster.Node;
import io.atomix.cluster.discovery.BootstrapDiscoveryBuilder;
import io.atomix.cluster.discovery.BootstrapDiscoveryProvider;
import io.atomix.cluster.discovery.NodeDiscoveryProvider;
import io.atomix.cluster.protocol.GroupMembershipProtocol;
import io.atomix.cluster.protocol.SwimMembershipProtocol;
import io.atomix.core.Atomix;
import io.atomix.core.AtomixBuilder;
import io.atomix.core.AtomixConfig;
import io.atomix.raft.partition.RaftPartitionGroup;
import io.atomix.raft.partition.RaftPartitionGroup.Builder;
import io.atomix.utils.net.Address;
import io.camunda.zeebe.broker.Loggers;
import io.camunda.zeebe.broker.system.configuration.BrokerCfg;
import io.camunda.zeebe.broker.system.configuration.ClusterCfg;
import io.camunda.zeebe.broker.system.configuration.DataCfg;
import io.camunda.zeebe.broker.system.configuration.MembershipCfg;
import io.camunda.zeebe.broker.system.configuration.NetworkCfg;
import io.camunda.zeebe.logstreams.impl.log.ZeebeEntryValidator;
import io.camunda.zeebe.snapshots.ReceivableSnapshotStoreFactory;
import io.camunda.zeebe.util.FileUtil;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;

public final class AtomixFactory {

  public static final String GROUP_NAME = "raft-partition";

  private static final Logger LOG = Loggers.CLUSTERING_LOGGER;

  private AtomixFactory() {}

  public static Atomix fromConfiguration(
      final BrokerCfg configuration, final ReceivableSnapshotStoreFactory snapshotStoreFactory) {
    final var clusterCfg = configuration.getCluster();
    final var nodeId = clusterCfg.getNodeId();
    final var localMemberId = Integer.toString(nodeId);
    final var networkCfg = configuration.getNetwork();

    final NodeDiscoveryProvider discoveryProvider =
        createDiscoveryProvider(clusterCfg, localMemberId);

    final MembershipCfg membershipCfg = clusterCfg.getMembership();
    final GroupMembershipProtocol membershipProtocol =
        SwimMembershipProtocol.builder()
            .withFailureTimeout(membershipCfg.getFailureTimeout())
            .withGossipInterval(membershipCfg.getGossipInterval())
            .withProbeInterval(membershipCfg.getProbeInterval())
            .withProbeTimeout(membershipCfg.getProbeTimeout())
            .withBroadcastDisputes(membershipCfg.isBroadcastDisputes())
            .withBroadcastUpdates(membershipCfg.isBroadcastUpdates())
            .withGossipFanout(membershipCfg.getGossipFanout())
            .withNotifySuspect(membershipCfg.isNotifySuspect())
            .withSuspectProbes(membershipCfg.getSuspectProbes())
            .withSyncInterval(membershipCfg.getSyncInterval())
            .build();

    final AtomixBuilder atomixBuilder =
        Atomix.builder(new AtomixConfig())
            .withClusterId(clusterCfg.getClusterName())
            .withMemberId(localMemberId)
            .withMembershipProtocol(membershipProtocol)
            .withMessagingInterface(networkCfg.getInternalApi().getHost())
            .withMessagingPort(networkCfg.getInternalApi().getPort())
            .withAddress(
                Address.from(
                    networkCfg.getInternalApi().getAdvertisedHost(),
                    networkCfg.getInternalApi().getAdvertisedPort()))
            .withMembershipProvider(discoveryProvider);

    final DataCfg dataConfiguration = configuration.getData();
    final String rootDirectory = dataConfiguration.getDirectory();
    try {
      FileUtil.ensureDirectoryExists(new File(rootDirectory).toPath());
    } catch (final IOException e) {
      throw new UncheckedIOException("Failed to create data directory", e);
    }

    final RaftPartitionGroup partitionGroup =
        createRaftPartitionGroup(configuration, rootDirectory, snapshotStoreFactory);

    return atomixBuilder.withPartitionGroup(partitionGroup).build();
  }

  private static RaftPartitionGroup createRaftPartitionGroup(
      final BrokerCfg configuration,
      final String rootDirectory,
      final ReceivableSnapshotStoreFactory snapshotStoreFactory) {

    final File raftDirectory = new File(rootDirectory, AtomixFactory.GROUP_NAME);
    try {
      FileUtil.ensureDirectoryExists(raftDirectory.toPath());
    } catch (final IOException e) {
      throw new UncheckedIOException("Failed to create raft directory", e);
    }

    final ClusterCfg clusterCfg = configuration.getCluster();
    final var experimentalCfg = configuration.getExperimental();
    final DataCfg dataCfg = configuration.getData();
    final NetworkCfg networkCfg = configuration.getNetwork();

    final Builder partitionGroupBuilder =
        RaftPartitionGroup.builder(AtomixFactory.GROUP_NAME)
            .withNumPartitions(clusterCfg.getPartitionsCount())
            .withPartitionSize(clusterCfg.getReplicationFactor())
            .withMembers(getRaftGroupMembers(clusterCfg))
            .withDataDirectory(raftDirectory)
            .withSnapshotStoreFactory(snapshotStoreFactory)
            .withMaxAppendBatchSize((int) experimentalCfg.getMaxAppendBatchSizeInBytes())
            .withMaxAppendsPerFollower(experimentalCfg.getMaxAppendsPerFollower())
            .withHeartbeatInterval(clusterCfg.getHeartbeatInterval())
            .withElectionTimeout(clusterCfg.getElectionTimeout())
            .withEntryValidator(new ZeebeEntryValidator())
            .withFlushExplicitly(!experimentalCfg.isDisableExplicitRaftFlush())
            .withFreeDiskSpace(dataCfg.getFreeDiskSpaceReplicationWatermark())
            .withJournalIndexDensity(dataCfg.getLogIndexDensity())
            .withPriorityElection(experimentalCfg.isEnablePriorityElection())
            .withRequestTimeout(experimentalCfg.getRaft().getRequestTimeout())
            .withMaxQuorumResponseTimeout(experimentalCfg.getRaft().getMaxQuorumResponseTimeout())
            .withMinStepDownFailureCount(experimentalCfg.getRaft().getMinStepDownFailureCount());

    final int maxMessageSize = (int) networkCfg.getMaxMessageSizeInBytes();

    final var segmentSize = dataCfg.getLogSegmentSizeInBytes();
    if (segmentSize < maxMessageSize) {
      throw new IllegalArgumentException(
          String.format(
              "Expected the raft segment size greater than the max message size of %s, but was %s.",
              maxMessageSize, segmentSize));
    }

    partitionGroupBuilder.withSegmentSize(segmentSize);

    return partitionGroupBuilder.build();
  }

  private static List<String> getRaftGroupMembers(final ClusterCfg clusterCfg) {
    final int clusterSize = clusterCfg.getClusterSize();
    // node ids are always 0 to clusterSize - 1
    final List<String> members = new ArrayList<>();
    for (int i = 0; i < clusterSize; i++) {
      members.add(Integer.toString(i));
    }
    return members;
  }

  private static NodeDiscoveryProvider createDiscoveryProvider(
      final ClusterCfg clusterCfg, final String localMemberId) {
    final BootstrapDiscoveryBuilder builder = BootstrapDiscoveryProvider.builder();
    final List<String> initialContactPoints = clusterCfg.getInitialContactPoints();

    final List<Node> nodes = new ArrayList<>();
    initialContactPoints.forEach(
        contactAddress -> {
          final Node node = Node.builder().withAddress(Address.from(contactAddress)).build();
          LOG.debug("Member {} will contact node: {}", localMemberId, node.address());
          nodes.add(node);
        });
    return builder.withNodes(nodes).build();
  }
}
