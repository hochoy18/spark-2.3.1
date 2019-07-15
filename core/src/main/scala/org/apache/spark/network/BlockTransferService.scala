/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.network

import java.io.Closeable
import java.nio.ByteBuffer

import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.Duration
import scala.reflect.ClassTag

import org.apache.spark.internal.Logging
import org.apache.spark.network.buffer.{FileSegmentManagedBuffer, ManagedBuffer, NioManagedBuffer}
import org.apache.spark.network.shuffle.{BlockFetchingListener, ShuffleClient, TempFileManager}
import org.apache.spark.storage.{BlockId, StorageLevel}
import org.apache.spark.util.ThreadUtils

private[spark]
abstract class BlockTransferService extends ShuffleClient with Closeable with Logging {

  /**
   * Initialize the transfer service by giving it the BlockDataManager that can be used to fetch
   * local blocks or put local blocks.
    * {@Author hochoy}
    * 通过提供可以用来获取本地块或放置本地块的BlockDataManager来初始化传输服务。
   */
  def init(blockDataManager: BlockDataManager): Unit

  /**
   * Tear down the transfer service.
   */
  def close(): Unit

  /**
   * Port number the service is listening on, available only after [[init]] is invoked.
    * {@Author hochoy} 服务正在监听的端口号，只有在[[init]]调用后才可用
   */
  def port: Int

  /**
   * Host name the service is listening on, available only after [[init]] is invoked.
   */
  def hostName: String

  /**
   * Fetch a sequence of blocks from a remote node asynchronously,
   * available only after [[init]] is invoked.
   *
   * Note that this API takes a sequence so the implementation can batch requests, and does not
   * return a future so the underlying implementation can invoke onBlockFetchSuccess as soon as
   * the data of a block is fetched, rather than waiting for all blocks to be fetched.
   *  {@Author hochoy}
   *  以异步方式从远程节点获取块序列
   * 请注意，这个API采用了一个序列，因此实现可以批量请求，而且不会返回一个future，因此底层实现可以在一个块的数据被获取时调用
    onBlockFetchSuccess，而不是等待所有的块都被获取。
   *
   *
    *
    */
  override def fetchBlocks(
      host: String,
      port: Int,
      execId: String,
      blockIds: Array[String],
      listener: BlockFetchingListener,
      tempFileManager: TempFileManager): Unit

  /**
   * Upload a single block to a remote node, available only after [[init]] is invoked.
    *
    * {@Author hochoy}
    * 将单个块上载到远程节点，
   */
  def uploadBlock(
      hostname: String,
      port: Int,
      execId: String,
      blockId: BlockId,
      blockData: ManagedBuffer,
      level: StorageLevel,
      classTag: ClassTag[_]): Future[Unit]

  /**
   * A special case of [[fetchBlocks]], as it fetches only one block and is blocking.
   *  {@Author hochoy}
   * [[fetchBlocks]] 的一种特殊形式，因为它只读取一个块并且阻塞。
   * * It is also only available after [[init]] is invoked.
   */
  def fetchBlockSync(
      host: String,
      port: Int,
      execId: String,
      blockId: String,
      tempFileManager: TempFileManager): ManagedBuffer = {
    // A monitor for the thread to wait on.
    val result = Promise[ManagedBuffer]()
    fetchBlocks(host, port, execId, Array(blockId),
      new BlockFetchingListener {
        override def onBlockFetchFailure(blockId: String, exception: Throwable): Unit = {
          result.failure(exception)
        }
        override def onBlockFetchSuccess(blockId: String, data: ManagedBuffer): Unit = {
          data match {
            case f: FileSegmentManagedBuffer =>
              result.success(f)
            case _ =>
              val ret = ByteBuffer.allocate(data.size.toInt)
              ret.put(data.nioByteBuffer())
              ret.flip()
              result.success(new NioManagedBuffer(ret))
          }
        }
      }, tempFileManager)
    ThreadUtils.awaitResult(result.future, Duration.Inf)
  }

  /**
   * Upload a single block to a remote node, available only after [[init]] is invoked.
   *
   * This method is similar to [[uploadBlock]], except this one blocks the thread
   * until the upload finishes.
   */
  def uploadBlockSync(
      hostname: String,
      port: Int,
      execId: String,
      blockId: BlockId,
      blockData: ManagedBuffer,
      level: StorageLevel,
      classTag: ClassTag[_]): Unit = {
    val future = uploadBlock(hostname, port, execId, blockId, blockData, level, classTag)
    ThreadUtils.awaitResult(future, Duration.Inf)
  }
}
