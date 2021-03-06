/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.broker.it.network;

import static org.assertj.core.api.Assertions.assertThat;

import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.ZeebeClientBuilder;
import io.camunda.zeebe.client.api.response.Topology;
import io.camunda.zeebe.test.util.asserts.TopologyAssert;
import io.camunda.zeebe.test.util.testcontainers.ZeebeTestContainerDefaults;
import io.zeebe.containers.ZeebeBrokerNode;
import io.zeebe.containers.ZeebeGatewayNode;
import io.zeebe.containers.ZeebeNode;
import io.zeebe.containers.ZeebePort;
import io.zeebe.containers.cluster.ZeebeCluster;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.containers.ToxiproxyContainer.ContainerProxy;
import org.testcontainers.utility.DockerImageName;

/**
 * Use ToxiProxy. Even though it does not support UDP, we can still use ToxiProxy because only
 * "gossip" messages use UDP. SWIM has other messages to probe and sync that uses TCP. So the
 * brokers can still find each other.
 */
public class AdvertisedAddressTest {
  private static final String TOXIPROXY_NETWORK_ALIAS = "toxiproxy";
  private static final String TOXIPROXY_IMAGE = "shopify/toxiproxy:2.1.0";
  @Rule public final Network network = Network.newNetwork();

  @Rule
  public final ToxiproxyContainer toxiproxy =
      new ToxiproxyContainer(DockerImageName.parse(TOXIPROXY_IMAGE))
          .withNetwork(network)
          .withNetworkAliases(TOXIPROXY_NETWORK_ALIAS);

  private final List<String> initialContactPoints = new ArrayList<>();
  private final ZeebeCluster cluster =
      ZeebeCluster.builder()
          .withNetwork(network)
          .withEmbeddedGateway(false)
          .withGatewaysCount(1)
          .withBrokersCount(3)
          .withPartitionsCount(1)
          .withReplicationFactor(3)
          .build();

  @Before
  public void setup() {
    cluster.getBrokers().values().forEach(this::configureBroker);
    cluster.getGateways().values().forEach(this::configureGateway);
  }

  @After
  public void tearDown() {
    cluster.stop();
  }

  @Test
  public void shouldCommunicateOverProxy() {
    // given
    cluster.start();

    // when - send a message to verify the gateway can talk to the broker directly not just via
    // gossip
    try (final var client = newClientBuilder().build()) {
      final Topology topology = client.newTopologyRequest().send().join(5, TimeUnit.SECONDS);
      final var messageSend =
          client
              .newPublishMessageCommand()
              .messageName("test")
              .correlationKey("test")
              .send()
              .join(5, TimeUnit.SECONDS);

      // then - gateway can talk to the broker
      final var proxiedPorts =
          cluster.getBrokers().values().stream()
              .map(ZeebeNode::getInternalHost)
              .map(host -> toxiproxy.getProxy(host, ZeebePort.COMMAND.getPort()))
              .map(ContainerProxy::getOriginalProxyPort)
              .collect(Collectors.toList());
      TopologyAssert.assertThat(topology)
          .isComplete(3, 1)
          .hasBrokerSatisfying(
              b ->
                  assertThat(b.getAddress())
                      .as("broker 0 advertises the correct proxied address")
                      .isEqualTo(TOXIPROXY_NETWORK_ALIAS + ":" + proxiedPorts.get(0)))
          .hasBrokerSatisfying(
              b ->
                  assertThat(b.getAddress())
                      .as("broker 1 advertises the correct proxied address")
                      .isEqualTo(TOXIPROXY_NETWORK_ALIAS + ":" + proxiedPorts.get(1)))
          .hasBrokerSatisfying(
              b ->
                  assertThat(b.getAddress())
                      .as("broker 2 advertises the correct proxied address")
                      .isEqualTo(TOXIPROXY_NETWORK_ALIAS + ":" + proxiedPorts.get(2)));
      assertThat(messageSend.getMessageKey()).isPositive();
    }
  }

  private void configureBroker(final ZeebeBrokerNode<?> broker) {
    final String hostName = broker.getInternalHost();
    final var commandApiProxy = toxiproxy.getProxy(hostName, ZeebePort.COMMAND.getPort());
    final var internalApiProxy = toxiproxy.getProxy(hostName, ZeebePort.INTERNAL.getPort());
    final var monitoringApiProxy = toxiproxy.getProxy(hostName, ZeebePort.MONITORING.getPort());

    initialContactPoints.add(
        TOXIPROXY_NETWORK_ALIAS + ":" + internalApiProxy.getOriginalProxyPort());

    broker.setDockerImageName(
        ZeebeTestContainerDefaults.defaultTestImage().asCanonicalNameString());
    broker
        .withEnv(
            "ZEEBE_BROKER_CLUSTER_INITIALCONTACTPOINTS", String.join(",", initialContactPoints))
        .withEnv("ZEEBE_LOG_LEVEL", "DEBUG")
        .withEnv("ATOMIX_LOG_LEVEL", "INFO")
        .withEnv("ZEEBE_BROKER_NETWORK_COMMANDAPI_ADVERTISEDHOST", TOXIPROXY_NETWORK_ALIAS)
        .withEnv(
            "ZEEBE_BROKER_NETWORK_COMMANDAPI_ADVERTISEDPORT",
            String.valueOf(commandApiProxy.getOriginalProxyPort()))
        .withEnv("ZEEBE_BROKER_NETWORK_INTERNALAPI_ADVERTISEDHOST", TOXIPROXY_NETWORK_ALIAS)
        .withEnv(
            "ZEEBE_BROKER_NETWORK_INTERNALAPI_ADVERTISEDPORT",
            String.valueOf(internalApiProxy.getOriginalProxyPort()))
        .withEnv("ZEEBE_BROKER_NETWORK_MONITORINGAPI_ADVERTISEDHOST", TOXIPROXY_NETWORK_ALIAS)
        .withEnv(
            "ZEEBE_BROKER_NETWORK_MONITORINGAPI_ADVERTISEDPORT",
            String.valueOf(monitoringApiProxy.getOriginalProxyPort()))
        // Since gossip does not work with ToxiProxy, increase the sync interval so changes are
        // propagated faster
        .withEnv("ZEEBE_BROKER_CLUSTER_MEMBERSHIP_SYNCINTERVAL", "100ms");
  }

  private void configureGateway(final ZeebeGatewayNode<?> gateway) {
    // gateway is not behind proxy
    final ZeebeBrokerNode<?> contactPoint = cluster.getBrokers().get(0);
    final ContainerProxy contactPointProxy =
        toxiproxy.getProxy(contactPoint.getInternalHost(), ZeebePort.INTERNAL.getPort());

    gateway.withEnv(
        "ZEEBE_GATEWAY_CLUSTER_CONTACTPOINT",
        TOXIPROXY_NETWORK_ALIAS + ":" + contactPointProxy.getOriginalProxyPort());
    gateway.setDockerImageName(
        ZeebeTestContainerDefaults.defaultTestImage().asCanonicalNameString());
  }

  // TODO(menski): replace with test container after update to new package names
  private ZeebeClientBuilder newClientBuilder() {
    final ZeebeGatewayNode<?> gateway =
        cluster.getGateways().values().stream()
            .filter(ZeebeNode::isStarted)
            .findAny()
            .orElseThrow(
                () ->
                    new NoSuchElementException(
                        "Expected at least one gateway for the client to connect to, but there is none"));

    return ZeebeClient.newClientBuilder()
        .gatewayAddress(gateway.getExternalGatewayAddress())
        .usePlaintext();
  }
}
