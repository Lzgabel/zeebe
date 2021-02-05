/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.engine.state;

import io.zeebe.msgpack.UnpackedObject;
import io.zeebe.protocol.record.intent.Intent;

/** Applies state changes for a specific event to the {@link io.zeebe.engine.state.ZeebeState}. */
public interface EventApplier<I extends Intent, V extends UnpackedObject> {

  void applyState(final long key, final V value);
}
