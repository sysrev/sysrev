package co.insilica.sysrev.spark

import java.util.Properties
import co.insilica.dataProvider.pg.{DBConfig => PgConfig}
import co.insilica.sysrev.{SysrevConfig, Config}

import org.apache.spark.SparkContext
import org.apache.spark.sql.{DataFrame, DataFrameReader, SQLContext}
import scala.language.implicitConversions

import scalaz._

trait ConfigContext[C] {
  implicit def config: Config
  implicit val context: C
  implicit def pg = config.pg
}

object readers {
  type ScReader[T] = Reader[SparkContext, T]
  type ConfigSQL = ConfigContext[SQLContext]
  type ConfigSQLReader[T] = Reader[ConfigSQL, T]


  def contextReader : ScReader[SparkContext] = Reader(identity)
  def withContext : ConfigSQLReader[ConfigSQL] = Reader(identity)
  type SQLTransaction[T] = ConfigSQLReader[T]

  def opts(implicit config : PgConfig) = Map(
    "url" -> s"jdbc:postgresql://${config.hostPort}/${config.dbName}",
    "driver" -> "org.postgresql.Driver",
    "user" -> config.name,
    "password" -> config.password
  )

  def props(implicit config : PgConfig) = {
    val p = new Properties()
    opts.foreach{ case (k, v) => p.setProperty(k, v) }
    p
  }

  def dataFrameBuilder : ConfigSQLReader[String => DataFrame] = Reader{ ctx => tableName =>
    import ctx._
    context.read
    .format("jdbc")
    .options(opts + ("dbtable" -> tableName))
    .load()
  }

  trait SparkOps[C, T]{
    def reader: Reader[C, T]
    def go(implicit context: C) : T = reader(context)
  }

  object SparkOps{
    def apply[C, T](r: Reader[C, T]) = new SparkOps[C, T]{def reader = r}
  }

  implicit def sqlContextToSparkOps[T](r: SQLTransaction[T]) = SparkOps(r)
  implicit def scContextToSparkOps[T](r: ScReader[T]) = SparkOps(r)


}
