/*
 * Copyright 2020 Jack Henry & Associates, Inc.®
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.banno.cosmos4s

import cats._
import cats.effect._
import cats.syntax.all._
import io.circe.Json
import com.microsoft.azure.documentdb.{DocumentClient, PartitionKeyDefinition}
import com.microsoft.azure.documentdb.bulkexecutor.{BulkImportResponse, DocumentBulkExecutor}

trait CosmosBulkClient[F[_], V] {
  def insert(value: List[V]): F[Unit]
  def upsert(value: List[V]): F[Unit]

  def mapK[G[_]](fk: F ~> G): CosmosBulkClient[G, V] =
    new CosmosBulkClient.MapKCosmosBulkClient[F, G, V](this, fk)
  def contramapValue[A](f: A => V): CosmosBulkClient[F, A] =
    new CosmosBulkClient.ContramapValue[F, A, V](this, f)
}

object CosmosBulkClient {

  /**
   * https://docs.microsoft.com/en-us/azure/cosmos-db/bulk-executor-java
   * It is recommended to instantiate a single DocumentBulkExecutor object for the entire application
   * within a single virtual machine that corresponds to a specific Azure Cosmos container.
   */
  def impl[F[_]: Sync](
      client: DocumentClient,
      database: String,
      collection: String,
      partitionKey: PartitionKeyDefinition,
      offerThroughput: Int,
      maxConcurrencyPerPartitionRange: Int
  ): Resource[F, CosmosBulkClient[F, Json]] =
    Resource
      .fromAutoCloseable(
        Sync[F]
          .delay {
            DocumentBulkExecutor
              .builder()
              .from(client, database, collection, partitionKey, offerThroughput)
              .build
          }
      )
      .map(new Impl[F](_, maxConcurrencyPerPartitionRange))

  private class Impl[F[_]: Sync](
      executor: DocumentBulkExecutor,
      maxConcurrencyPerPartitionRange: Int
  ) extends CosmosBulkClient[F, Json] {
    import scala.collection.JavaConverters._

    def insert(value: List[Json]): F[Unit] =
      Sync[F].delay(
        executor.importAll(
          value.map(_.noSpaces).asJava,
          false,
          true,
          maxConcurrencyPerPartitionRange)) >>= { r =>
        if (r.getNumberOfDocumentsImported() == value.size)
          ().pure[F]
        else
          CosmosBulkInsertFailure(r).raiseError
      }

    def upsert(value: List[Json]): F[Unit] =
      Sync[F].delay(executor
        .importAll(value.map(_.noSpaces).asJava, true, true, maxConcurrencyPerPartitionRange)) >>= {
        r =>
          if (r.getNumberOfDocumentsImported() == value.size)
            ().pure[F]
          else
            CosmosBulkUpsertFailure(r).raiseError
      }

  }

  sealed trait CosmosBulkClientFailure extends RuntimeException with Product with Serializable
  final case class CosmosBulkInsertFailure(response: BulkImportResponse)
      extends CosmosBulkClientFailure
  final case class CosmosBulkUpsertFailure(response: BulkImportResponse)
      extends CosmosBulkClientFailure

  private class MapKCosmosBulkClient[F[_], G[_], V](
      base: CosmosBulkClient[F, V],
      fk: F ~> G
  ) extends CosmosBulkClient[G, V] {
    def insert(value: List[V]): G[Unit] = fk(base.insert(value))
    def upsert(value: List[V]): G[Unit] = fk(base.upsert(value))
  }

  private class ContramapValue[F[_], V2, V](
      base: CosmosBulkClient[F, V],
      contra: V2 => V
  ) extends CosmosBulkClient[F, V2] {
    def insert(value: List[V2]): F[Unit] = base.insert(value.map(contra))
    def upsert(value: List[V2]): F[Unit] = base.upsert(value.map(contra))
  }

}
