/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.namenode;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.fs.permission.PermissionStatus;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.QuotaExceededException;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockCollection;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockInfo;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockInfoUnderConstruction;
import org.apache.hadoop.hdfs.server.blockmanagement.DatanodeDescriptor;
import org.apache.hadoop.hdfs.server.blockmanagement.DatanodeStorageInfo;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants.BlockUCState;
import org.apache.hadoop.hdfs.server.namenode.snapshot.FileDiff;
import org.apache.hadoop.hdfs.server.namenode.snapshot.FileDiffList;
import org.apache.hadoop.hdfs.server.namenode.snapshot.FileWithSnapshotFeature;
import org.apache.hadoop.hdfs.server.namenode.snapshot.Snapshot;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;

/** I-node for closed file. */
@InterfaceAudience.Private
public class INodeFile extends INodeWithAdditionalFields
    implements INodeFileAttributes, BlockCollection {

  /** The same as valueOf(inode, path, false). */
  public static INodeFile valueOf(INode inode, String path
      ) throws FileNotFoundException {
    return valueOf(inode, path, false);
  }

  /** Cast INode to INodeFile. */
  public static INodeFile valueOf(INode inode, String path, boolean acceptNull)
      throws FileNotFoundException {
    if (inode == null) {
      if (acceptNull) {
        return null;
      } else {
        throw new FileNotFoundException("File does not exist: " + path);
      }
    }
    if (!inode.isFile()) {
      throw new FileNotFoundException("Path is not a file: " + path);
    }
    return inode.asFile();
  }

  /** Format: [16 bits for replication][48 bits for PreferredBlockSize] */
  static class HeaderFormat {
    /** Number of bits for Block size */
    static final int BLOCKBITS = 48;
    /** Header mask 64-bit representation */
    static final long HEADERMASK = 0xffffL << BLOCKBITS;
    static final long MAX_BLOCK_SIZE = ~HEADERMASK; 
    
    static short getReplication(long header) {
      return (short) ((header & HEADERMASK) >> BLOCKBITS);
    }

    static long combineReplication(long header, short replication) {
      if (replication <= 0) {
         throw new IllegalArgumentException(
             "Unexpected value for the replication: " + replication);
      }
      return ((long)replication << BLOCKBITS) | (header & MAX_BLOCK_SIZE);
    }
    
    static long getPreferredBlockSize(long header) {
      return header & MAX_BLOCK_SIZE;
    }

    static long combinePreferredBlockSize(long header, long blockSize) {
      if (blockSize < 0) {
         throw new IllegalArgumentException("Block size < 0: " + blockSize);
      } else if (blockSize > MAX_BLOCK_SIZE) {
        throw new IllegalArgumentException("Block size = " + blockSize
            + " > MAX_BLOCK_SIZE = " + MAX_BLOCK_SIZE);
     }
      return (header & HEADERMASK) | (blockSize & MAX_BLOCK_SIZE);
    }
  }

  private long header = 0L;

  private BlockInfo[] blocks;

  INodeFile(long id, byte[] name, PermissionStatus permissions, long mtime,
      long atime, BlockInfo[] blklist, short replication,
      long preferredBlockSize) {
    super(id, name, permissions, mtime, atime);
    header = HeaderFormat.combineReplication(header, replication);
    header = HeaderFormat.combinePreferredBlockSize(header, preferredBlockSize);
    this.blocks = blklist;
  }
  
  public INodeFile(INodeFile that) {
    super(that);
    this.header = that.header;
    this.blocks = that.blocks;
    this.features = that.features;
  }
  
  public INodeFile(INodeFile that, FileDiffList diffs) {
    this(that);
    Preconditions.checkArgument(!that.isWithSnapshot());
    this.addSnapshotFeature(diffs);
  }

  /** @return true unconditionally. */
  @Override
  public final boolean isFile() {
    return true;
  }

  /** @return this object. */
  @Override
  public final INodeFile asFile() {
    return this;
  }

  /* Start of Under-Construction Feature */

  /**
   * If the inode contains a {@link FileUnderConstructionFeature}, return it;
   * otherwise, return null.
   */
  public final FileUnderConstructionFeature getFileUnderConstructionFeature() {
    for (Feature f : features) {
      if (f instanceof FileUnderConstructionFeature) {
        return (FileUnderConstructionFeature) f;
      }
    }
    return null;
  }

  /** Is this file under construction? */
  @Override // BlockCollection
  public boolean isUnderConstruction() {
    return getFileUnderConstructionFeature() != null;
  }

  /** Convert this file to an {@link INodeFileUnderConstruction}. */
  INodeFile toUnderConstruction(String clientName, String clientMachine,
      DatanodeDescriptor clientNode) {
    Preconditions.checkState(!isUnderConstruction(),
        "file is already under construction");
    FileUnderConstructionFeature uc = new FileUnderConstructionFeature(
        clientName, clientMachine, clientNode);
    addFeature(uc);
    return this;
  }

  /**
   * Convert the file to a complete file, i.e., to remove the Under-Construction
   * feature.
   */
  public INodeFile toCompleteFile(long mtime) {
    Preconditions.checkState(isUnderConstruction(),
        "file is no longer under construction");
    FileUnderConstructionFeature uc = getFileUnderConstructionFeature();
    if (uc != null) {
      assertAllBlocksComplete();
      removeFeature(uc);
      this.setModificationTime(mtime);
    }
    return this;
  }

  /** Assert all blocks are complete. */
  private void assertAllBlocksComplete() {
    if (blocks == null) {
      return;
    }
    for (int i = 0; i < blocks.length; i++) {
      Preconditions.checkState(blocks[i].isComplete(), "Failed to finalize"
          + " %s %s since blocks[%s] is non-complete, where blocks=%s.",
          getClass().getSimpleName(), this, i, Arrays.asList(blocks));
    }
  }

  @Override // BlockCollection
  public void setBlock(int index, BlockInfo blk) {
    this.blocks[index] = blk;
  }

  @Override // BlockCollection, the file should be under construction
  public BlockInfoUnderConstruction setLastBlock(BlockInfo lastBlock,
      DatanodeStorageInfo[] locations) throws IOException {
    Preconditions.checkState(isUnderConstruction(),
        "file is no longer under construction");

    if (numBlocks() == 0) {
      throw new IOException("Failed to set last block: File is empty.");
    }
    BlockInfoUnderConstruction ucBlock =
      lastBlock.convertToBlockUnderConstruction(
          BlockUCState.UNDER_CONSTRUCTION, locations);
    ucBlock.setBlockCollection(this);
    setBlock(numBlocks() - 1, ucBlock);
    return ucBlock;
  }

  /**
   * Remove a block from the block list. This block should be
   * the last one on the list.
   */
  boolean removeLastBlock(Block oldblock) {
    Preconditions.checkState(isUnderConstruction(),
        "file is no longer under construction");
    if (blocks == null || blocks.length == 0) {
      return false;
    }
    int size_1 = blocks.length - 1;
    if (!blocks[size_1].equals(oldblock)) {
      return false;
    }

    //copy to a new list
    BlockInfo[] newlist = new BlockInfo[size_1];
    System.arraycopy(blocks, 0, newlist, 0, size_1);
    setBlocks(newlist);
    return true;
  }

  /* End of Under-Construction Feature */
  
  /* Start of Snapshot Feature */

  private FileWithSnapshotFeature addSnapshotFeature(FileDiffList diffs) {
    Preconditions.checkState(!isWithSnapshot(), 
        "File is already with snapshot");
    FileWithSnapshotFeature sf = new FileWithSnapshotFeature(diffs);
    this.addFeature(sf);
    return sf;
  }
  
  /**
   * If feature list contains a {@link FileWithSnapshotFeature}, return it;
   * otherwise, return null.
   */
  public final FileWithSnapshotFeature getFileWithSnapshotFeature() {
    for (Feature f: features) {
      if (f instanceof FileWithSnapshotFeature) {
        return (FileWithSnapshotFeature) f;
      }
    }
    return null;
  }

  /** Is this file has the snapshot feature? */
  public final boolean isWithSnapshot() {
    return getFileWithSnapshotFeature() != null;
  }
    
  @Override
  public String toDetailString() {
    FileWithSnapshotFeature sf = this.getFileWithSnapshotFeature();
    return super.toDetailString() + (sf == null ? "" : sf.getDetailedString()); 
  }

  @Override
  public INodeFileAttributes getSnapshotINode(final Snapshot snapshot) {
    FileWithSnapshotFeature sf = this.getFileWithSnapshotFeature();
    if (sf != null) {
      return sf.getDiffs().getSnapshotINode(snapshot, this);
    } else {
      return this;
    }
  }

  @Override
  public INodeFile recordModification(final Snapshot latest) 
      throws QuotaExceededException {
    if (isInLatestSnapshot(latest) && !shouldRecordInSrcSnapshot(latest)) {
      // the file is in snapshot, create a snapshot feature if it does not have
      FileWithSnapshotFeature sf = this.getFileWithSnapshotFeature();
      if (sf == null) {
        sf = addSnapshotFeature(null);
      }
      // record self in the diff list if necessary
      sf.getDiffs().saveSelf2Snapshot(latest, this, null);
    }
    return this;
  }
  
  public FileDiffList getDiffs() {
    FileWithSnapshotFeature sf = this.getFileWithSnapshotFeature();
    if (sf != null) {
      return sf.getDiffs();
    }
    return null;
  }
  
  /* End of Snapshot Feature */

  /** @return the replication factor of the file. */
  public final short getFileReplication(Snapshot snapshot) {
    if (snapshot != null) {
      return getSnapshotINode(snapshot).getFileReplication();
    }

    return HeaderFormat.getReplication(header);
  }

  /** The same as getFileReplication(null). */
  @Override // INodeFileAttributes
  public final short getFileReplication() {
    return getFileReplication(null);
  }

  @Override // BlockCollection
  public short getBlockReplication() {
    short max = getFileReplication(null);
    FileWithSnapshotFeature sf = this.getFileWithSnapshotFeature();
    if (sf != null) {
      short maxInSnapshot = sf.getMaxBlockRepInDiffs();
      if (sf.isCurrentFileDeleted()) {
        return maxInSnapshot;
      }
      max = maxInSnapshot > max ? maxInSnapshot : max;
    }
    return max;
  }

  /** Set the replication factor of this file. */
  public final void setFileReplication(short replication) {
    header = HeaderFormat.combineReplication(header, replication);
  }

  /** Set the replication factor of this file. */
  public final INodeFile setFileReplication(short replication, Snapshot latest,
      final INodeMap inodeMap) throws QuotaExceededException {
    final INodeFile nodeToUpdate = recordModification(latest);
    nodeToUpdate.setFileReplication(replication);
    return nodeToUpdate;
  }

  /** @return preferred block size (in bytes) of the file. */
  @Override
  public long getPreferredBlockSize() {
    return HeaderFormat.getPreferredBlockSize(header);
  }

  @Override
  public long getHeaderLong() {
    return header;
  }

  /** @return the diskspace required for a full block. */
  final long getBlockDiskspace() {
    return getPreferredBlockSize() * getBlockReplication();
  }

  /** @return the blocks of the file. */
  @Override
  public BlockInfo[] getBlocks() {
    return this.blocks;
  }

  void updateBlockCollection() {
    if (blocks != null) {
      for(BlockInfo b : blocks) {
        b.setBlockCollection(this);
      }
    }
  }

  /**
   * append array of blocks to this.blocks
   */
  void concatBlocks(INodeFile[] inodes) {
    int size = this.blocks.length;
    int totalAddedBlocks = 0;
    for(INodeFile f : inodes) {
      totalAddedBlocks += f.blocks.length;
    }
    
    BlockInfo[] newlist = new BlockInfo[size + totalAddedBlocks];
    System.arraycopy(this.blocks, 0, newlist, 0, size);
    
    for(INodeFile in: inodes) {
      System.arraycopy(in.blocks, 0, newlist, size, in.blocks.length);
      size += in.blocks.length;
    }

    setBlocks(newlist);
    updateBlockCollection();
  }
  
  /**
   * add a block to the block list
   */
  void addBlock(BlockInfo newblock) {
    if (this.blocks == null) {
      this.setBlocks(new BlockInfo[]{newblock});
    } else {
      int size = this.blocks.length;
      BlockInfo[] newlist = new BlockInfo[size + 1];
      System.arraycopy(this.blocks, 0, newlist, 0, size);
      newlist[size] = newblock;
      this.setBlocks(newlist);
    }
  }

  /** Set the blocks. */
  public void setBlocks(BlockInfo[] blocks) {
    this.blocks = blocks;
  }

  @Override
  public Quota.Counts cleanSubtree(final Snapshot snapshot, Snapshot prior,
      final BlocksMapUpdateInfo collectedBlocks,
      final List<INode> removedINodes, final boolean countDiffChange)
      throws QuotaExceededException {
    FileWithSnapshotFeature sf = getFileWithSnapshotFeature();
    if (sf != null) {
      return sf.cleanFile(this, snapshot, prior, collectedBlocks,
          removedINodes, countDiffChange);
    }
    Quota.Counts counts = Quota.Counts.newInstance();
    if (snapshot == null && prior == null) {
      // this only happens when deleting the current file and the file is not
      // in any snapshot
      computeQuotaUsage(counts, false);
      destroyAndCollectBlocks(collectedBlocks, removedINodes);
    } else if (snapshot == null && prior != null) {
      // when deleting the current file and the file is in snapshot, we should
      // clean the 0-sized block if the file is UC
      FileUnderConstructionFeature uc = getFileUnderConstructionFeature();
      if (uc != null) {
        uc.cleanZeroSizeBlock(this, collectedBlocks);
      }
    }
    return counts;
  }

  @Override
  public void destroyAndCollectBlocks(BlocksMapUpdateInfo collectedBlocks,
      final List<INode> removedINodes) {
    if (blocks != null && collectedBlocks != null) {
      for (BlockInfo blk : blocks) {
        collectedBlocks.addDeleteBlock(blk);
        blk.setBlockCollection(null);
      }
    }
    setBlocks(null);
    clear();
    removedINodes.add(this);
    
    FileWithSnapshotFeature sf = getFileWithSnapshotFeature();
    if (sf != null) {
      sf.clearDiffs();
    }
  }
  
  @Override
  public String getName() {
    // Get the full path name of this inode.
    return getFullPathName();
  }

  @Override
  public final Quota.Counts computeQuotaUsage(Quota.Counts counts,
      boolean useCache, int lastSnapshotId) {
    long nsDelta = 1;
    final long dsDelta;
    FileWithSnapshotFeature sf = getFileWithSnapshotFeature();
    if (sf != null) {
      FileDiffList fileDiffList = sf.getDiffs();
      Snapshot last = fileDiffList.getLastSnapshot();
      List<FileDiff> diffs = fileDiffList.asList();

      if (lastSnapshotId == Snapshot.INVALID_ID || last == null) {
        nsDelta += diffs.size();
        dsDelta = diskspaceConsumed();
      } else if (last.getId() < lastSnapshotId) {
        dsDelta = computeFileSize(true, false) * getFileReplication();
      } else {      
        Snapshot s = fileDiffList.getSnapshotById(lastSnapshotId);
        dsDelta = diskspaceConsumed(s);
      }
    } else {
      dsDelta = diskspaceConsumed();
    }
    counts.add(Quota.NAMESPACE, nsDelta);
    counts.add(Quota.DISKSPACE, dsDelta);
    return counts;
  }

  @Override
  public final ContentSummaryComputationContext  computeContentSummary(
      final ContentSummaryComputationContext summary) {
    computeContentSummary4Snapshot(summary.getCounts());
    computeContentSummary4Current(summary.getCounts());
    return summary;
  }

  private void computeContentSummary4Snapshot(final Content.Counts counts) {
    // file length and diskspace only counted for the latest state of the file
    // i.e. either the current state or the last snapshot
    FileWithSnapshotFeature sf = getFileWithSnapshotFeature();
    if (sf != null) {
      final FileDiffList diffs = sf.getDiffs();
      final int n = diffs.asList().size();
      counts.add(Content.FILE, n);
      if (n > 0 && sf.isCurrentFileDeleted()) {
        counts.add(Content.LENGTH, diffs.getLast().getFileSize());
      }

      if (sf.isCurrentFileDeleted()) {
        final long lastFileSize = diffs.getLast().getFileSize();
        counts.add(Content.DISKSPACE, lastFileSize * getBlockReplication());
      }
    }
  }

  private void computeContentSummary4Current(final Content.Counts counts) {
    FileWithSnapshotFeature sf = this.getFileWithSnapshotFeature();
    if (sf != null && sf.isCurrentFileDeleted()) {
      return;
    }

    counts.add(Content.LENGTH, computeFileSize());
    counts.add(Content.FILE, 1);
    counts.add(Content.DISKSPACE, diskspaceConsumed());
  }

  /** The same as computeFileSize(null). */
  public final long computeFileSize() {
    return computeFileSize(null);
  }

  /**
   * Compute file size of the current file if the given snapshot is null;
   * otherwise, get the file size from the given snapshot.
   */
  public final long computeFileSize(Snapshot snapshot) {
    FileWithSnapshotFeature sf = this.getFileWithSnapshotFeature();
    if (snapshot != null && sf != null) {
      final FileDiff d = sf.getDiffs().getDiff(
          snapshot);
      if (d != null) {
        return d.getFileSize();
      }
    }

    return computeFileSize(true, false);
  }

  /**
   * Compute file size of the current file size
   * but not including the last block if it is under construction.
   */
  public final long computeFileSizeNotIncludingLastUcBlock() {
    return computeFileSize(false, false);
  }

  /**
   * Compute file size of the current file.
   * 
   * @param includesLastUcBlock
   *          If the last block is under construction, should it be included?
   * @param usePreferredBlockSize4LastUcBlock
   *          If the last block is under construction, should we use actual
   *          block size or preferred block size?
   *          Note that usePreferredBlockSize4LastUcBlock is ignored
   *          if includesLastUcBlock == false.
   * @return file size
   */
  public final long computeFileSize(boolean includesLastUcBlock,
      boolean usePreferredBlockSize4LastUcBlock) {
    if (blocks == null || blocks.length == 0) {
      return 0;
    }
    final int last = blocks.length - 1;
    //check if the last block is BlockInfoUnderConstruction
    long size = blocks[last].getNumBytes();
    if (blocks[last] instanceof BlockInfoUnderConstruction) {
       if (!includesLastUcBlock) {
         size = 0;
       } else if (usePreferredBlockSize4LastUcBlock) {
         size = getPreferredBlockSize();
       }
    }
    //sum other blocks
    for(int i = 0; i < last; i++) {
      size += blocks[i].getNumBytes();
    }
    return size;
  }

  public final long diskspaceConsumed() {
    // use preferred block size for the last block if it is under construction
    return computeFileSize(true, true) * getBlockReplication();
  }

  public final long diskspaceConsumed(Snapshot lastSnapshot) {
    if (lastSnapshot != null) {
      return computeFileSize(lastSnapshot) * getFileReplication(lastSnapshot);
    } else {
      return diskspaceConsumed();
    }
  }
  
  /**
   * Return the penultimate allocated block for this file.
   */
  BlockInfo getPenultimateBlock() {
    if (blocks == null || blocks.length <= 1) {
      return null;
    }
    return blocks[blocks.length - 2];
  }

  @Override
  public BlockInfo getLastBlock() throws IOException {
    return blocks == null || blocks.length == 0? null: blocks[blocks.length-1];
  }

  @Override
  public int numBlocks() {
    return blocks == null ? 0 : blocks.length;
  }

  @VisibleForTesting
  @Override
  public void dumpTreeRecursively(PrintWriter out, StringBuilder prefix,
      final Snapshot snapshot) {
    super.dumpTreeRecursively(out, prefix, snapshot);
    out.print(", fileSize=" + computeFileSize(snapshot));
    // only compare the first block
    out.print(", blocks=");
    out.print(blocks == null || blocks.length == 0? null: blocks[0]);
    out.println();
  }
}
