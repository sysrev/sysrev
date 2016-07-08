package co.insilica.sysrev.spark

import org.apache.spark.SparkContext
import org.apache.spark.sql.SQLContext
import scala.language.implicitConversions

import scalaz._


object readers {
  type ScReader[T] = Reader[SparkContext, T]
  type SQLReader[T] = Reader[SQLContext, T]

  type SQLTransaction[T] = SQLReader[T]
  def contextReader : ScReader[SparkContext] = Reader(identity)
  def withSqlContext : SQLReader[SQLContext] = Reader(identity)

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
