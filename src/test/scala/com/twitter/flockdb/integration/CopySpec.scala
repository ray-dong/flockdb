/*
 * Copyright 2010 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.twitter.flockdb
package integration

import scala.collection.JavaConversions._
import scala.collection.mutable
import com.twitter.gizzard.thrift.conversions.ShardInfo._
import com.twitter.gizzard.scheduler.{JsonJob, PrioritizingJobScheduler}
import com.twitter.gizzard.thrift.conversions.Sequences._
import com.twitter.gizzard.shards.{ShardInfo, ShardId, Busy, RoutingNode}
import com.twitter.gizzard.nameserver.Forwarding
import com.twitter.util.Time
import com.twitter.util.TimeConversions._
import org.specs.mock.{ClassMocker, JMocker}
import com.twitter.flockdb
import com.twitter.flockdb.Edge
import com.twitter.flockdb.config.{FlockDB => FlockDBConfig}
import shards.{Shard, SqlShard}
import thrift._

class CopySpec extends IntegrationSpecification {
  "Copy" should {
    val sourceShardId = ShardId("localhost", "copy_test1")
    val destinationShardId = ShardId("localhost", "copy_test2")
    val shard3Id = ShardId("localhost", "copy_test3")
    val sourceShardInfo = new ShardInfo(sourceShardId,
            "com.twitter.flockdb.SqlShard", "INT UNSIGNED", "INT UNSIGNED", Busy.Normal)
    val destinationShardInfo = new ShardInfo(destinationShardId,
            "com.twitter.flockdb.SqlShard", "INT UNSIGNED", "INT UNSIGNED", Busy.Normal)
    val shard3Info = new ShardInfo(shard3Id,
          "com.twitter.flockdb.SqlShard", "INT UNSIGNED", "INT UNSIGNED", Busy.Normal)
    val time = Time.now

    doBefore {
      flock.nameServer.reload()
      flock.shardManager.createAndMaterializeShard(sourceShardInfo)
      flock.shardManager.createAndMaterializeShard(destinationShardInfo)
      flock.shardManager.createAndMaterializeShard(shard3Info)
      flock.shardManager.setForwarding(new Forwarding(0, Long.MinValue, sourceShardInfo.id))
      
    }

    doAfter {
       val queryEvaluator = config.edgesQueryEvaluator()(config.databaseConnection)
       queryEvaluator.execute("DROP TABLE IF EXISTS copy_test1_edges")
       queryEvaluator.execute("DROP TABLE IF EXISTS copy_test1_metadata")
       queryEvaluator.execute("DROP TABLE IF EXISTS copy_test2_edges")
       queryEvaluator.execute("DROP TABLE IF EXISTS copy_test2_metadata")
       queryEvaluator.execute("DROP TABLE IF EXISTS copy_test3_edges")
       queryEvaluator.execute("DROP TABLE IF EXISTS copy_test3_metadata")
    }

    def writeEdges(shard: RoutingNode[Shard], num: Int, start: Int, step: Int, outdated: Boolean) {
      val edges = mutable.ArrayBuffer[Edge]()
      (start to num by step).foreach { id => 
        edges += Edge(1L, id.toLong, id.toLong, (if (outdated) time-1.seconds else time), 0, State.Normal)
      }

      shard.writeOperation(_.writeCopies(edges))
    }

    def getEdges(shard: RoutingNode[Shard], num: Int) {
      shard.readOperation(_.count(1L, Seq(State.Normal))) must eventually(be_==(num))
    }

    def validateEdges(shards: Seq[RoutingNode[Shard]], num: Int) {
      Thread.sleep(20000)
      shards.foreach { getEdges(_, num) }
      val shardsEdges = shards map { _.readOperation(_.selectAll((Cursor.Start, Cursor.Start), 2*num))._1}
      var i = 1
      shardsEdges.foreach { edges =>
        println("checking "+i+": "+ (edges.length))
        i+=1

      }
      shardsEdges.foreach { 
        _.length must eventually(be_==(num)) }
      (0 until num).foreach { idx => 
        var j = 1
        shardsEdges.foldLeft(shardsEdges(0)) { case (lastEdges, edges) => 
          j+=1
          lastEdges(idx) must be_==(edges(idx))
          if (edges(idx).updatedAt.inSeconds != time.inSeconds) {
            println("DANGER WILL ROBINSON shard " + j + " row " + (idx+1))
            println("shard 1: "+ shardsEdges(0)(idx))
            println("shard 2: "+ shardsEdges(1)(idx))
            println("shard 3: "+ shardsEdges(2)(idx))

          }
          edges(idx).updatedAt.inSeconds must be_==(time.inSeconds)
          edges
        }
      }
    }

    // "copy" in {
    //   val numData = 20000
    //   val sourceShard = flock.nameServer.findShardById[Shard](sourceShardId)
    //   val destinationShard = flock.nameServer.findShardById[Shard](destinationShardId)
    //   writeEdges(sourceShard, numData, 1, 1, false)
    //   getEdges(sourceShard, numData)
    //   getEdges(destinationShard, 0)

    //   flock.managerServer.copy_shard(Seq(sourceShardInfo.toThrift.id, destinationShardInfo.toThrift.id))

    //   getEdges(destinationShard, numData)

    // }

    // "repair by merging" in {
    //   val numData = 20000
    //   val shard1 = flock.nameServer.findShardById[Shard](sourceShardId)
    //   val shard2 = flock.nameServer.findShardById[Shard](destinationShardId)
    //   writeEdges(shard1, numData, 1, 2, false)
    //   writeEdges(shard2, numData, 2, 2, false)

    //   flock.managerServer.copy_shard(Seq(sourceShardInfo.toThrift.id, destinationShardInfo.toThrift.id))

    //   validateEdges(Seq(shard1, shard2), numData)
    // }

    "repair out of date" in {
      val numData = 25000
      
      val shard1 = flock.nameServer.findShardById[Shard](sourceShardId)
      val shard2 = flock.nameServer.findShardById[Shard](destinationShardId)
      val shard3 = flock.nameServer.findShardById[Shard](shard3Id)

      writeEdges(shard1, numData, 1, 2, false)
      writeEdges(shard2, numData/2, 2, 2, false)
      writeEdges(shard2, numData, numData/2, 1, false)
      writeEdges(shard3, numData, 1, 3, true)

      flock.managerServer.copy_shard(Seq(sourceShardInfo.toThrift.id, destinationShardInfo.toThrift.id, shard3Info.toThrift.id))

      validateEdges(Seq(shard1, shard2, shard3), numData)

    }


  }
  
}
