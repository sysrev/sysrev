package co.insilica.sysrev
import scala.language.implicitConversions

import java.io.File
import java.net.{URL, URI}
import co.insilica.dataProvider.config.{Config => DefaultConfig, Overrider, ConfigFileHandler}
import co.insilica.dataProvider.pg.connection.Implicits.fromConfig
import co.insilica.dataProvider.mongo.{Config => MongoConfig}
import co.insilica.dataProvider.pg.{DBConfig => PgConfig}

import reactivemongo.bson._
import scalaz.concurrent.Task

import scalaz._
import Scalaz._

import doobie.imports._

case class Config(
  dataRootPath: Option[String],
  mongo: MongoConfig,
  pg: PgConfig,
  citationsXmlFileName: String,
  criteriaCsvFileName: String
){
  def citationsURI: Option[URI] = dataRootPath.map(new File(_, citationsXmlFileName).toURI)
  def citationsURL: Option[URL] = this.citationsURI.map(_.toURL)
}


trait SysrevConfig {
  def fileHandler : ConfigFileHandler[Config]

  implicit object ConfigOverrider extends Overrider[DefaultConfig, Config] {
    // NOTE: FOR EACH FIELD, ADD LINE TO COPY IT BELOW
    def overrideWith(c1: DefaultConfig, c2: Config) : Config = Config(
      c2.dataRootPath.orElse(c1.dataRootPath),
      c2.mongo,
      c2.pg,
      c2.citationsXmlFileName,
      c2.criteriaCsvFileName
    )

    def defaults(c1: DefaultConfig): Config = Config(
      c1.dataRootPath,
      c1.mongo,
      c1.pg,
      "",
      ""
    )
  }

  implicit lazy val config : Config = fileHandler.readConfig()

  lazy val transactor : Transactor[Task] = fromConfig(config.pg)

  implicit val mongoConfig = config.mongo

  implicit lazy val tx = transactor
}

object SysrevConfig{
  implicit val default = new SysrevConfig {
    lazy val fileHandler : ConfigFileHandler[Config] = new ConfigFileHandler[Config]{
      override def customFileName: String = ".insilica/sysrev/config.json"
    }
  }

  def apply(filename: String) : SysrevConfig = new SysrevConfig{
    lazy val fileHandler: ConfigFileHandler[Config] = new ConfigFileHandler[Config]{
      override def customFileName: String = filename
    }
  }
}
