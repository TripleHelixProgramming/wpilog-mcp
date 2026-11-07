/*
 * Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
 * SPDX-License-Identifier: MIT
 */
package org.triplehelix.wpilogmcp.capture;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.triplehelix.wpilogmcp.store.PublicationPendingException;

/** Matching words never stand in for the store owner's completed safe recovery proof. */
class CapturePublicationClassificationTest {
  @ParameterizedTest @ValueSource(ints = {0, 1, 2})
  void onlyTheTypedCompletePublicationOutcomeMayContinue(int wrappers) {
    Throwable pending = new PublicationPendingException("Replica publication remains pending");
    assertNull(CaptureService.publicationMayRemainPending(wrap(pending, wrappers)));
  }

  @ParameterizedTest @ValueSource(ints = {0, 1, 2})
  void ordinaryIoFailureIsStillFatalEvenWhenItsTextResemblesPendingPublication(int wrappers) {
    var unsafe = new IOException("RESOURCE_LIMIT: capture is recorded; replica publication is pending");
    var refused = assertThrows(CompletionException.class,
        () -> CaptureService.publicationMayRemainPending(wrap(unsafe, wrappers)));
    assertSame(unsafe, refused.getCause(), "Only a typed completed sweep may be tolerated");
  }

  @ParameterizedTest @ValueSource(ints = {0, 1, 2})
  void unrelatedFailureWrappingPendingDoesNotBecomeACompletedSweep(int wrappers) {
    var unsafe = new IOException("Recovery is incomplete", new PublicationPendingException("Pending"));
    var refused = assertThrows(CompletionException.class,
        () -> CaptureService.publicationMayRemainPending(wrap(unsafe, wrappers)));
    assertSame(unsafe, refused.getCause(), "Unwrapping is limited to asynchronous future wrappers");
  }

  private static Throwable wrap(Throwable failure, int count) {
    return switch (count) {
      case 0 -> failure;
      case 1 -> new CompletionException(failure);
      default -> new ExecutionException(new CompletionException(failure));
    };
  }
}
