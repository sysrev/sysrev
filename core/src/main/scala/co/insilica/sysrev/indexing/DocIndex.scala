package co.insilica
package sysrev
package indexing


import co.insilica.dataProvider.mongo.connection.Implicits.config._
import dataProvider.mongo.connectAsync
import play.api.libs.iteratee.Iteratee
import scala.concurrent.{Future, ExecutionContext}
import co.insilica.sysrev.Types._
import co.insilica.dataProvider.mongo.{Config => MongoConfig}

object sysrevImporter {
  def apply()(implicit config: MongoConfig): Importer = new Importer{
    def collection(implicit ec: ExecutionContext) = connectAsync("sysrev")
  }
}

object DocIndex {

  def all[T]()(implicit env: QueryEnv[T], config: MongoConfig): Future[List[T]] = {
    import env._
    sysrevImporter().select().flatMap{ enum =>
      enum |>>> Iteratee.getChunks
    }
  }

  def index()(implicit ec: ExecutionContext, config: MongoConfig) : Future[Seq[SysRev]] = {
    sysrevImporter().select[SysRev]().flatMap{ enum =>
      enum |>>> Iteratee.takeUpTo(5)
    }
  }
}


