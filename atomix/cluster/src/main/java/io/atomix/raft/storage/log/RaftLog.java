/*
 * Copyright 2017-present Open Networking Foundation
 * Copyright © 2020 camunda services GmbH (info@camunda.com)
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
package io.atomix.raft.storage.log;

import io.atomix.raft.storage.log.RaftLogReader.Mode;
import io.atomix.raft.storage.log.entry.ApplicationEntry;
import io.atomix.raft.storage.log.entry.ConfigurationEntry;
import io.atomix.raft.storage.log.entry.InitialEntry;
import io.atomix.raft.storage.log.entry.RaftLogEntry;
import io.atomix.raft.storage.serializer.RaftEntrySBESerializer;
import io.atomix.raft.storage.serializer.RaftEntrySerializer;
import io.camunda.zeebe.journal.Journal;
import io.camunda.zeebe.journal.JournalRecord;
import java.io.Closeable;
import org.agrona.CloseHelper;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/** Raft log. */
public final class RaftLog implements Closeable {
  private final Journal journal;
  private final RaftEntrySerializer serializer = new RaftEntrySBESerializer();
  private final boolean flushExplicitly;

  private IndexedRaftLogEntry lastAppendedEntry;
  private volatile long commitIndex;

  private final MutableDirectBuffer writeBuffer = new ExpandableArrayBuffer(4 * 1024);

  RaftLog(final Journal journal, final boolean flushExplicitly) {
    this.journal = journal;
    this.flushExplicitly = flushExplicitly;
  }

  /**
   * Returns a new Raft log builder.
   *
   * @return A new Raft log builder.
   */
  public static RaftLogBuilder builder() {
    return new RaftLogBuilder();
  }

  /**
   * Opens the reader with {@link Mode} ALL.
   *
   * @return the reader
   */
  public RaftLogReader openReader() {
    return openReader(Mode.ALL);
  }

  /**
   * Opens the reader with given {@link Mode}.
   *
   * @param mode the mode of the reader
   * @return the reader
   */
  public RaftLogReader openReader(final Mode mode) {
    final RaftLogReader reader = new RaftLogReader(this, journal.openReader(), mode);
    return reader;
  }

  public boolean isOpen() {
    return journal.isOpen();
  }

  /**
   * Compacts the journal up to the given index.
   *
   * <p>The semantics of compaction are not specified by this interface.
   *
   * @param index The index up to which to compact the journal.
   */
  public void deleteUntil(final long index) {
    journal.deleteUntil(index);
  }

  /**
   * Returns the Raft log commit index.
   *
   * @return The Raft log commit index.
   */
  public long getCommitIndex() {
    return commitIndex;
  }

  /**
   * Commits entries up to the given index.
   *
   * @param index The index up to which to commit entries.
   */
  public void setCommitIndex(final long index) {
    commitIndex = index;
  }

  public boolean shouldFlushExplicitly() {
    return flushExplicitly;
  }

  public long getFirstIndex() {
    return journal.getFirstIndex();
  }

  public long getLastIndex() {
    return journal.getLastIndex();
  }

  public IndexedRaftLogEntry getLastEntry() {
    if (lastAppendedEntry == null) {
      readLastEntry();
    }

    return lastAppendedEntry;
  }

  private void readLastEntry() {
    try (final var reader = openReader()) {
      reader.seekToLast();
      if (reader.hasNext()) {
        lastAppendedEntry = reader.next();
      }
    }
  }

  public boolean isEmpty() {
    return journal.isEmpty();
  }

  public IndexedRaftLogEntry append(final RaftLogEntry entry) {
    final JournalRecord journalRecord;

    if (entry.isApplicationEntry()) {
      final ApplicationEntry asqnEntry = entry.getApplicationEntry();
      final int serializedLength =
          serializer.writeApplicationEntry(entry.term(), asqnEntry, writeBuffer, 0);
      journalRecord =
          journal.append(
              asqnEntry.lowestPosition(), new UnsafeBuffer(writeBuffer, 0, serializedLength));
    } else if (entry.isInitialEntry()) {
      final InitialEntry initialEntry = entry.getInitialEntry();
      final int serializedLength =
          serializer.writeInitialEntry(entry.term(), initialEntry, writeBuffer, 0);
      journalRecord = journal.append(new UnsafeBuffer(writeBuffer, 0, serializedLength));
    } else if (entry.isConfigurationEntry()) {
      final ConfigurationEntry configurationEntry = entry.getConfigurationEntry();
      final int serializedLength =
          serializer.writeConfigurationEntry(entry.term(), configurationEntry, writeBuffer, 0);
      journalRecord = journal.append(new UnsafeBuffer(writeBuffer, 0, serializedLength));
    } else {
      throw new IllegalArgumentException("Unexpected entry type " + entry);
    }

    lastAppendedEntry = new IndexedRaftLogEntryImpl(entry.term(), entry.entry(), journalRecord);
    return lastAppendedEntry;
  }

  public IndexedRaftLogEntry append(final PersistedRaftRecord entry) {
    journal.append(entry);

    final RaftLogEntry raftEntry = serializer.readRaftLogEntry(entry.data());
    lastAppendedEntry = new IndexedRaftLogEntryImpl(entry.term(), raftEntry.entry(), entry);
    return lastAppendedEntry;
  }

  public void reset(final long index) {
    journal.reset(index);
    lastAppendedEntry = null;
  }

  public void deleteAfter(final long index) {
    if (index < commitIndex) {
      throw new IllegalStateException(
          String.format(
              "Expected to delete index after %d, but it is lower than the commit index %d. Deleting committed entries can lead to inconsistencies and is prohibited.",
              index, commitIndex));
    }
    journal.deleteAfter(index);
    lastAppendedEntry = null;
  }

  public void flush() {
    if (flushExplicitly) {
      journal.flush();
    }
  }

  @Override
  public void close() {
    CloseHelper.close(journal);
  }

  @Override
  public String toString() {
    return "RaftLog{"
        + "journal="
        + journal
        + ", serializer="
        + serializer
        + ", flushExplicitly="
        + flushExplicitly
        + ", lastAppendedEntry="
        + lastAppendedEntry
        + ", commitIndex="
        + commitIndex
        + '}';
  }
}
