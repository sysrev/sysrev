package co.insilica.sysrev


import java.io.File
import java.net.{URL, URI}

import co.insilica.dataProvider.config.{Config => DefaultConfig, Overrider, ConfigFileHandler}
import co.insilica.dataProvider.mongo.{Config => MongoConfig}
import co.insilica.dataProvider.pg.{DBConfig => PgConfig}
import co.insilica.dataProvider.solr.{Config => SolrConfig}
import doobie.imports._
import upickle.default._

import scalaz.concurrent.Task


object Implicits {
  case class Config(
    dataRootPath: Option[String],
    mongo: MongoConfig,
    pg: PgConfig,
    solr: SolrConfig,
    citationsXmlFileName: String
  ){
    def citationsURI: Option[URI] = dataRootPath.map(new File(_, citationsXmlFileName).toURI)
    def citationsURL: Option[URL] = this.citationsURI.map(_.toURL)
  }

  implicit object ConfigOverrider extends Overrider[DefaultConfig, Config] {
    // NOTE: FOR EACH FIELD, ADD LINE TO COPY IT BELOW
    def overrideWith(c1: DefaultConfig, c2: Config) : Config = Config(
      c2.dataRootPath.orElse(c1.dataRootPath),
      c2.mongo,
      c2.pg,
      c2.solr,
      c2.citationsXmlFileName
    )

    def defaults(c1: DefaultConfig): Config = Config(
      c1.dataRootPath,
      c1.mongo,
      c1.pg,
      c1.solr,
      ""
    )
  }

  val fileHandler = new ConfigFileHandler[Config] {
    override def customFileName: String = ".insilica/sysrev/config.json"
  }

  implicit lazy val config : Config = fileHandler.readConfig()

  implicit lazy val transactor : Transactor[Task] = co.insilica.dataProvider.pg.connection.Implicits.fromConfig(config.pg)

  implicit val mongoConfig = config.mongo
}