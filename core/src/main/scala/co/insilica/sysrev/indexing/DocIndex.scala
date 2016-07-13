package co.insilica
package sysrev
package indexing

import Implicits._
import co.insilica.dataProvider.mongo.connection.Implicits.config._
import dataProvider.mongo.connectAsync
import play.api.libs.iteratee.Iteratee

import scala.concurrent.{Future, ExecutionContext}


object sysrevImporter extends Importer{
  def collection(implicit ec: ExecutionContext) = connectAsync("sysrev")
}

object DocIndex {
  def all[T]()(implicit env: QueryEnv[T]): Future[List[T]] = {
    import env._
    sysrevImporter.select().flatMap{ enum =>
      enum |>>> Iteratee.getChunks
    }
  }

  def index()(implicit ec: ExecutionContext) : Future[Seq[SysRev]] = {
    sysrevImporter.select[SysRev]().flatMap{ enum =>
      enum |>>> Iteratee.takeUpTo(5)
    }
  }
}


