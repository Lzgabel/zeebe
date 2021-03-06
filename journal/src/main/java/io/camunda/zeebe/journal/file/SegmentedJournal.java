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
package io.camunda.zeebe.journal.file;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.collect.Sets;
import io.camunda.zeebe.journal.Journal;
import io.camunda.zeebe.journal.JournalException;
import io.camunda.zeebe.journal.JournalReader;
import io.camunda.zeebe.journal.JournalRecord;
import io.camunda.zeebe.journal.file.record.CorruptedLogException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.locks.StampedLock;
import org.agrona.DirectBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A file based journal. The journal is split into multiple segments files. */
public class SegmentedJournal implements Journal {

  public static final long ASQN_IGNORE = -1;
  private static final int SEGMENT_BUFFER_FACTOR = 3;
  private static final int FIRST_SEGMENT_ID = 1;
  private static final int INITIAL_INDEX = 1;
  private final JournalMetrics journalMetrics;
  private final Logger log = LoggerFactory.getLogger(getClass());
  private final String name;
  private final File directory;
  private final int maxSegmentSize;
  private final NavigableMap<Long, JournalSegment> segments = new ConcurrentSkipListMap<>();
  private final Collection<SegmentedJournalReader> readers = Sets.newConcurrentHashSet();
  private volatile JournalSegment currentSegment;
  private volatile boolean open = true;
  private final long minFreeDiskSpace;
  private final JournalIndex journalIndex;
  private final SegmentedJournalWriter writer;
  private final long lastWrittenIndex;
  private final StampedLock rwlock = new StampedLock();

  public SegmentedJournal(
      final String name,
      final File directory,
      final int maxSegmentSize,
      final long minFreeSpace,
      final JournalIndex journalIndex,
      final long lastWrittenIndex) {
    this.name = checkNotNull(name, "name cannot be null");
    this.directory = checkNotNull(directory, "directory cannot be null");
    this.maxSegmentSize = maxSegmentSize;
    journalMetrics = new JournalMetrics(name);
    minFreeDiskSpace = minFreeSpace;
    this.journalIndex = journalIndex;
    this.lastWrittenIndex = lastWrittenIndex;
    open();
    writer = new SegmentedJournalWriter(this);
  }

  /**
   * Returns a new SegmentedJournal builder.
   *
   * @return A new Segmented journal builder.
   */
  public static SegmentedJournalBuilder builder() {
    return new SegmentedJournalBuilder();
  }

  @Override
  public JournalRecord append(final long asqn, final DirectBuffer data) {
    return writer.append(asqn, data);
  }

  @Override
  public JournalRecord append(final DirectBuffer data) {
    return writer.append(ASQN_IGNORE, data);
  }

  @Override
  public void append(final JournalRecord record) {
    writer.append(record);
  }

  @Override
  public void deleteAfter(final long indexExclusive) {
    journalMetrics.observeSegmentTruncation(
        () -> {
          final var stamp = rwlock.writeLock();
          try {
            writer.deleteAfter(indexExclusive);
            // Reset segment readers.
            resetAdvancedReaders(indexExclusive + 1);
          } finally {
            rwlock.unlockWrite(stamp);
          }
        });
  }

  @Override
  public void deleteUntil(final long index) {
    final Map.Entry<Long, JournalSegment> segmentEntry = segments.floorEntry(index);
    if (segmentEntry != null) {
      final SortedMap<Long, JournalSegment> compactSegments =
          segments.headMap(segmentEntry.getValue().index());
      if (compactSegments.isEmpty()) {
        log.debug(
            "No segments can be deleted with index < {} (first log index: {})",
            index,
            getFirstIndex());
        return;
      }

      log.debug(
          "{} - Deleting log up from {} up to {} (removing {} segments)",
          name,
          getFirstIndex(),
          compactSegments.get(compactSegments.lastKey()).index(),
          compactSegments.size());
      for (final JournalSegment segment : compactSegments.values()) {
        log.trace("{} - Deleting segment: {}", name, segment);
        segment.close();
        segment.delete();
        journalMetrics.decSegmentCount();
      }

      // removes them from the segment map
      compactSegments.clear();

      journalIndex.deleteUntil(index);
      resetHead(getFirstSegment().index());
    }
  }

  @Override
  public void reset(final long nextIndex) {
    final var stamp = rwlock.writeLock();
    try {
      journalIndex.clear();
      writer.reset(nextIndex);
      resetHead(nextIndex);
    } finally {
      rwlock.unlockWrite(stamp);
    }
  }

  @Override
  public long getLastIndex() {
    return writer.getLastIndex();
  }

  @Override
  public long getFirstIndex() {
    if (!segments.isEmpty()) {
      return segments.firstEntry().getValue().index();
    } else {
      return 0;
    }
  }

  @Override
  public boolean isEmpty() {
    return writer.getNextIndex() - getFirstSegment().index() == 0;
  }

  @Override
  public void flush() {
    writer.flush();
  }

  @Override
  public JournalReader openReader() {
    final SegmentedJournalReader reader = new SegmentedJournalReader(this);
    readers.add(reader);
    return reader;
  }

  @Override
  public boolean isOpen() {
    return open;
  }

  @Override
  public void close() {
    segments
        .values()
        .forEach(
            segment -> {
              log.debug("Closing segment: {}", segment);
              segment.close();
            });
    currentSegment = null;
    open = false;
  }

  /** Opens the segments. */
  private synchronized void open() {
    final long startTime = System.currentTimeMillis();
    // Load existing log segments from disk.
    for (final JournalSegment segment : loadSegments()) {
      segments.put(segment.descriptor().index(), segment);
      journalMetrics.incSegmentCount();
    }

    // If a segment doesn't already exist, create an initial segment starting at index 1.
    if (!segments.isEmpty()) {
      currentSegment = segments.lastEntry().getValue();
    } else {
      final JournalSegmentDescriptor descriptor =
          JournalSegmentDescriptor.builder()
              .withId(FIRST_SEGMENT_ID)
              .withIndex(INITIAL_INDEX)
              .withMaxSegmentSize(maxSegmentSize)
              .build();

      currentSegment = createSegment(descriptor);

      segments.put(1L, currentSegment);
      journalMetrics.incSegmentCount();
    }
    journalMetrics.observeJournalOpenDuration(System.currentTimeMillis() - startTime);
  }

  /**
   * Asserts that the journal is open.
   *
   * @throws IllegalStateException if the journal is not open
   */
  private void assertOpen() {
    checkState(currentSegment != null, "journal not open");
  }

  /** Asserts that enough disk space is available to allocate a new segment. */
  private void assertDiskSpace() {
    if (directory().getUsableSpace()
        < Math.max(maxSegmentSize() * SEGMENT_BUFFER_FACTOR, minFreeDiskSpace)) {
      throw new JournalException.OutOfDiskSpace(
          "Not enough space to allocate a new journal segment");
    }
  }

  private long maxSegmentSize() {
    return maxSegmentSize;
  }

  private File directory() {
    return directory;
  }

  /** Resets the current segment, creating a new segment if necessary. */
  private synchronized void resetCurrentSegment() {
    final JournalSegment lastSegment = getLastSegment();
    if (lastSegment != null) {
      currentSegment = lastSegment;
    } else {
      final JournalSegmentDescriptor descriptor =
          JournalSegmentDescriptor.builder()
              .withId(1)
              .withIndex(1)
              .withMaxSegmentSize(maxSegmentSize)
              .build();

      currentSegment = createSegment(descriptor);

      segments.put(1L, currentSegment);
      journalMetrics.incSegmentCount();
    }
  }

  /**
   * Resets and returns the first segment in the journal.
   *
   * @param index the starting index of the journal
   * @return the first segment
   */
  JournalSegment resetSegments(final long index) {
    assertOpen();

    for (final JournalSegment segment : segments.values()) {
      segment.close();
      segment.delete();
      journalMetrics.decSegmentCount();
    }
    segments.clear();

    final JournalSegmentDescriptor descriptor =
        JournalSegmentDescriptor.builder()
            .withId(1)
            .withIndex(index)
            .withMaxSegmentSize(maxSegmentSize)
            .build();
    currentSegment = createSegment(descriptor);
    segments.put(index, currentSegment);
    journalMetrics.incSegmentCount();
    return currentSegment;
  }

  /**
   * Returns the first segment in the log.
   *
   * @throws IllegalStateException if the segment manager is not open
   */
  JournalSegment getFirstSegment() {
    assertOpen();
    final Map.Entry<Long, JournalSegment> segment = segments.firstEntry();
    return segment != null ? segment.getValue() : null;
  }

  /**
   * Returns the last segment in the log.
   *
   * @throws IllegalStateException if the segment manager is not open
   */
  JournalSegment getLastSegment() {
    assertOpen();
    final Map.Entry<Long, JournalSegment> segment = segments.lastEntry();
    return segment != null ? segment.getValue() : null;
  }

  /**
   * Creates and returns the next segment.
   *
   * @return The next segment.
   * @throws IllegalStateException if the segment manager is not open
   */
  synchronized JournalSegment getNextSegment() {
    assertOpen();
    assertDiskSpace();

    final JournalSegment lastSegment = getLastSegment();
    final JournalSegmentDescriptor descriptor =
        JournalSegmentDescriptor.builder()
            .withId(lastSegment != null ? lastSegment.descriptor().id() + 1 : 1)
            .withIndex(currentSegment.lastIndex() + 1)
            .withMaxSegmentSize(maxSegmentSize)
            .build();

    currentSegment = createSegment(descriptor);

    segments.put(descriptor.index(), currentSegment);
    journalMetrics.incSegmentCount();
    return currentSegment;
  }

  /**
   * Returns the segment following the segment with the given ID.
   *
   * @param index The segment index with which to look up the next segment.
   * @return The next segment for the given index.
   */
  JournalSegment getNextSegment(final long index) {
    final Map.Entry<Long, JournalSegment> nextSegment = segments.higherEntry(index);
    return nextSegment != null ? nextSegment.getValue() : null;
  }

  /**
   * Returns the segment for the given index.
   *
   * @param index The index for which to return the segment.
   * @throws IllegalStateException if the segment manager is not open
   */
  synchronized JournalSegment getSegment(final long index) {
    assertOpen();
    // Check if the current segment contains the given index first in order to prevent an
    // unnecessary map lookup.
    if (currentSegment != null && index > currentSegment.index()) {
      return currentSegment;
    }

    // If the index is in another segment, get the entry with the next lowest first index.
    final Map.Entry<Long, JournalSegment> segment = segments.floorEntry(index);
    if (segment != null) {
      return segment.getValue();
    }
    return getFirstSegment();
  }

  /**
   * Removes a segment.
   *
   * @param segment The segment to remove.
   */
  synchronized void removeSegment(final JournalSegment segment) {
    segments.remove(segment.index());
    journalMetrics.decSegmentCount();
    segment.close();
    segment.delete();
    resetCurrentSegment();
  }

  /** Creates a new segment. */
  JournalSegment createSegment(final JournalSegmentDescriptor descriptor) {
    final File segmentFile = JournalSegmentFile.createSegmentFile(name, directory, descriptor.id());

    final RandomAccessFile raf;
    final FileChannel channel;
    try {
      raf = new RandomAccessFile(segmentFile, "rw");
      raf.setLength(descriptor.maxSegmentSize());
      channel = raf.getChannel();
    } catch (final IOException e) {
      throw new JournalException(e);
    }

    final ByteBuffer buffer = ByteBuffer.allocate(JournalSegmentDescriptor.getEncodingLength());
    descriptor.copyTo(buffer);
    try {
      channel.write(buffer);
    } catch (final IOException e) {
      throw new JournalException(e);
    } finally {
      try {
        channel.close();
        raf.close();
      } catch (final IOException e) {
        log.warn("Unexpected IOException on closing", e);
      }
    }
    final JournalSegment segment = newSegment(new JournalSegmentFile(segmentFile), descriptor);
    log.debug("Created segment: {}", segment);
    return segment;
  }

  /**
   * Creates a new segment instance.
   *
   * @param segmentFile The segment file.
   * @param descriptor The segment descriptor.
   * @return The segment instance.
   */
  protected JournalSegment newSegment(
      final JournalSegmentFile segmentFile, final JournalSegmentDescriptor descriptor) {
    return new JournalSegment(segmentFile, descriptor, lastWrittenIndex, journalIndex);
  }

  /** Loads a segment. */
  private JournalSegment loadSegment(final long segmentId) {
    final File segmentFile = JournalSegmentFile.createSegmentFile(name, directory, segmentId);
    final ByteBuffer buffer = ByteBuffer.allocate(JournalSegmentDescriptor.getEncodingLength());
    try (final FileChannel channel = openChannel(segmentFile)) {
      channel.read(buffer);
      final JournalSegmentDescriptor descriptor = new JournalSegmentDescriptor(buffer);
      final JournalSegment segment = newSegment(new JournalSegmentFile(segmentFile), descriptor);
      log.debug("Loaded disk segment: {} ({})", descriptor.id(), segmentFile.getName());
      return segment;
    } catch (final IOException e) {
      throw new JournalException(e);
    }
  }

  private FileChannel openChannel(final File file) {
    try {
      return FileChannel.open(
          file.toPath(),
          StandardOpenOption.CREATE,
          StandardOpenOption.READ,
          StandardOpenOption.WRITE);
    } catch (final IOException e) {
      throw new JournalException(e);
    }
  }

  /**
   * Loads all segments from disk.
   *
   * @return A collection of segments for the log.
   */
  protected Collection<JournalSegment> loadSegments() {
    // Ensure log directories are created.
    directory.mkdirs();
    final List<JournalSegment> segments = new ArrayList<>();

    final List<File> files = getSortedLogSegments();
    for (int i = 0; i < files.size(); i++) {
      final File file = files.get(i);

      try {
        log.debug("Found segment file: {}", file.getName());
        final JournalSegmentDescriptor descriptor = readDescriptor(file);
        final JournalSegment segment = loadSegment(descriptor.id());

        if (i > 0) {
          checkForIndexGaps(segments.get(i - 1), segment);
        }

        segments.add(segment);
      } catch (final CorruptedLogException e) {
        if (handleSegmentCorruption(files, segments, i)) {
          return segments;
        }

        throw e;
      }
    }

    return segments;
  }

  private void checkForIndexGaps(final JournalSegment prevSegment, final JournalSegment segment) {
    if (prevSegment.lastIndex() != segment.index() - 1) {
      throw new CorruptedLogException(
          String.format(
              "Log segment %s is not aligned with previous segment %s (last index: %d).",
              segment, prevSegment, prevSegment.lastIndex()));
    }
  }

  /** Returns true if segments after corrupted segment were deleted; false, otherwise */
  private boolean handleSegmentCorruption(
      final List<File> files, final List<JournalSegment> segments, final int failedIndex) {
    long lastSegmentIndex = 0;

    if (!segments.isEmpty()) {
      final JournalSegment previousSegment = segments.get(segments.size() - 1);
      lastSegmentIndex = previousSegment.lastIndex();
    }

    if (lastWrittenIndex > lastSegmentIndex) {
      return false;
    }

    log.debug(
        "Found corrupted segment after last ack'ed index {}. Deleting segments {} - {}",
        lastWrittenIndex,
        files.get(failedIndex).getName(),
        files.get(files.size() - 1).getName());

    for (int i = failedIndex; i < files.size(); i++) {
      final File file = files.get(i);
      try {
        Files.delete(file.toPath());
      } catch (IOException e) {
        throw new JournalException(
            String.format(
                "Failed to delete log segment '%s' when handling corruption.", file.getName()),
            e);
      }
    }

    return true;
  }

  /** Returns an array of valid log segments sorted by their id which may be empty but not null. */
  private List<File> getSortedLogSegments() {
    final File[] files =
        directory.listFiles(file -> file.isFile() && JournalSegmentFile.isSegmentFile(name, file));

    if (files == null) {
      throw new IllegalStateException(
          String.format(
              "Could not list files in directory '%s'. Either the path doesn't point to a directory or an I/O error occurred.",
              directory));
    }

    Arrays.sort(
        files, Comparator.comparingInt(f -> JournalSegmentFile.getSegmentIdFromPath(f.getName())));

    return Arrays.asList(files);
  }

  private JournalSegmentDescriptor readDescriptor(final File file) {
    final int descriptorLength = JournalSegmentDescriptor.getEncodingLength();
    if (file.length() < descriptorLength) {
      throw new CorruptedLogException(
          String.format(
              "Log segment is smaller than a segment descriptor (%d < %d).",
              file.length(), descriptorLength));
    }

    final ByteBuffer buffer = ByteBuffer.allocate(descriptorLength);
    try (final FileChannel channel = openChannel(file)) {
      final int readBytes = channel.read(buffer);

      if (readBytes != -1 && readBytes < descriptorLength) {
        throw new JournalException(
            String.format(
                "Expected to read segment descriptor (%d bytes) but only read %d bytes.",
                descriptorLength, readBytes));
      }
      buffer.flip();
    } catch (final IOException e) {
      throw new JournalException(e);
    }

    return new JournalSegmentDescriptor(buffer);
  }

  public void closeReader(final SegmentedJournalReader segmentedJournalReader) {
    readers.remove(segmentedJournalReader);
  }

  /**
   * Resets journal readers to the given head.
   *
   * @param index The index at which to reset readers.
   */
  void resetHead(final long index) {
    for (final SegmentedJournalReader reader : readers) {
      if (reader.getNextIndex() <= index) {
        reader.unsafeSeek(index);
      }
    }
  }

  /**
   * Resets journal readers to the given index, if they are at a larger index.
   *
   * @param index The index at which to reset readers.
   */
  void resetAdvancedReaders(final long index) {
    for (final SegmentedJournalReader reader : readers) {
      if (reader.getNextIndex() > index) {
        reader.unsafeSeek(index);
      }
    }
  }

  public JournalMetrics getJournalMetrics() {
    return journalMetrics;
  }

  public JournalIndex getJournalIndex() {
    return journalIndex;
  }

  long acquireReadlock() {
    return rwlock.readLock();
  }

  void releaseReadlock(final long stamp) {
    rwlock.unlockRead(stamp);
  }
}
