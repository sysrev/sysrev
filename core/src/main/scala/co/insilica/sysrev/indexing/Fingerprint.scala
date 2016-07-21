package co.insilica.sysrev
package indexing

import co.insilica.sysrev.spark
import org.apache.spark.ml.{Pipeline, Transformer}
import org.apache.spark.ml.feature.{VectorAssembler, HashingTF, IDF, Tokenizer}
import org.apache.spark.mllib.linalg.{SparseVector, Vectors}
import org.apache.spark.mllib.linalg.distributed.{CoordinateMatrix, RowMatrix}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{SaveMode, GroupedData, DataFrame, Row}

import spark.readers.dataFrameBuilder

import scalaz._
import Scalaz._
import Implicits.config.pg
import breeze.math._
import breeze.linalg._
import breeze.numerics._

import spark.Types.SparkVector

object Fingerprint {

  import spark.readers._
  import spark.alg._

  type DoubleVector = breeze.linalg.SparseVector[Double]
  val DoubleVector = breeze.linalg.SparseVector

  /**
    *    Emulating a query like this:
    *    select primary_title, secondary_title, abstract, answer
    *    from article
    *      left join article_criteria using (article_Id)
    *    left join criteria using (criteria_id)
    *    where name = 'overall include' or name is null
    */
  def queryForDataFrame: SQLTransaction[DataFrame] = withSqlContext map { context =>
    val makeDf = dataFrameBuilder(context)

    val articleDf = makeDf("article")
    val articleCriteriaDf = makeDf("article_criteria")
    val criteriaDf = makeDf("criteria")
    val keywordDf = makeDf("keyword")
    val articleKeywordDf = makeDf("article_keyword")

    // Assemble dataframe by joining and all the above and filtering
    // so we only have our target answer.
    articleDf
      .join(articleCriteriaDf, usingColumns = Seq("article_id"), joinType="left_outer")
      .join(criteriaDf, usingColumns = Seq("criteria_id"), joinType = "left_outer")
      //     .join(articleKeywordDf, usingColumn = "article_id")
      //      .join(keywordDf, usingColumn = "keyword_id")
      .filter("""name = 'overall include' or name is null""")
      .select("article_id", "primary_title", "secondary_title", "abstract", "answer")
  }

  def fillNullValues: DataFrame => DataFrame = { df => df.na.fill("") }

  def sysrevTfidfTransform: DataFrame => DataFrame = { qdf =>
    // Set up transformer pipeline, to be applied in order.
    val transformers: List[DataFrame => DataFrame] = List(
      tokenize(
        "abstract" -> "abstract words",
        "primary_title" -> "title1 words",
        "secondary_title" -> "title2 words"
      ),
      applyTfidf("title1 words" -> "title1 words tfidf", 262144),       // 2^18
      applyTfidf("abstract words" -> "abstract words tfidf", 1048576),  // 2^20
      applyTfidf("title2 words" -> "title2 words tfidf", 262144),
      new VectorAssembler()
        .setInputCols(Array("abstract words tfidf", "title1 words tfidf", "title2 words tfidf"))
        .setOutputCol("features").transform
    )

    transformers.foldLeft(qdf)(_ |> _)
  }


  /**
    * dumbRank takes a set of vectors pointing in a direction
    * we want to avoid, and a set of vectors pointing in a direction
    * we want to favor, an a set of vectors we wish to decide on,
    * and produces a ranking based on how much the vector points toward
    * our favored direction and away from our unfavored direction.
    *
    * So we'll take the vector average of the good (g) and bad (b), and rank U as
    * u dot g - u dot b
    *
    * I made this up, no idea if this is good or not.
    */
  def dumbRank[T](bad: RDD[(SparkVector, T)], good: RDD[(SparkVector, T)], unknown: RDD[(SparkVector, T)]): RDD[(T, Double)] = {
    val numbad = bad.count()
    val numgood = good.count()
    val numunk = unknown.count()

    def makeSparse(x: SparkVector): DoubleVector = {
      val sp = x.toSparse
      new breeze.linalg.SparseVector(sp.indices, sp.values, sp.size)
    }

    def makeDense(x: SparkVector): breeze.linalg.DenseVector[Double] = {
      val sp = x.toDense
      new DenseVector[Double](sp.values)
    }

    val bavg : DoubleVector = bad.map{ case (v, _) => makeSparse(v) }.reduce(_ + _) / numbad.toDouble

    val goodVectors: List[DoubleVector] = good.map{ case (v, _) => makeSparse(v) }.collect().toList

    val gavg : DoubleVector = goodVectors.reduce (_ + _)  / numgood.toDouble

    val proj : DoubleVector = gavg - ((gavg dot bavg) / (norm(bavg)))

    val sortCondition : DoubleVector => Double = test =>
      goodVectors.map(g => breeze.linalg.functions.cosineDistance(test, g)).min

    val merged = bad ++ good ++ unknown

    val scored : RDD[(T, Double)] = merged map {
      case (v, row) =>
        val vec = makeSparse(v)
        val score = sortCondition(vec)
        (row, score)
    }

    scored.sortBy(_._2)
  }

  def rankTransform: DataFrame => RDD[(Row, Double)] = { df =>
    df.cache()
    val dfcount = df.count()

    val good = df.filter("answer = true")
    val bad = df.filter("answer = false")
    val rest = df.filter("answer is null")

    def conv(rdd: RDD[Row]): RDD[(SparkVector, Row)] = rdd.map(r => (r.getAs[SparkVector]("features"), r))

    dumbRank(conv(bad.rdd), conv(good.rdd), conv(rest.rdd))
  }


  def rankJob : SQLTransaction[RDD[(Row, Double)]] = queryForDataFrame map (
    _ |> fillNullValues |> sysrevTfidfTransform |> rankTransform
  )

  def rankedDf: SQLTransaction[DataFrame] = withSqlContext map { context =>
    import context.implicits._
    rankJob(context).toDF
  }


}


// SparkConf -> SparkContext -> SQLContext -> T
// SparkContext -> T

object Test extends App{
  import spark.Implicits.local._
  import spark.readers._
  type Extractor = Row => String => String

  val textDelim = "\"\"\""
  def delimText(s: String): String = textDelim + s + textDelim
  val stringExtractor : Extractor = row => s => Option(row.getAs[String](s)).map(delimText).getOrElse("")
  val boolExtractor : Extractor = row => s => Option(row.getAs[Boolean](s)).map(_.toString).getOrElse("")

  def saveCsv(rdd: RDD[(Row, Double)], location: String): Unit = {
    val fieldNames : List[String] = List("primary_title", "secondary_title", "abstract", "answer")
    val extractors: List[Extractor] = List(stringExtractor, stringExtractor, stringExtractor, boolExtractor)


    // Todo, this is for some reason generating split columns in some cases?
    val csvRdd : RDD[String] = rdd.map{
      case (row, score) =>
        val fields: List[String] = fieldNames.zip(extractors).map{
          case (cell, ext) => ext(row)(cell)
        }
        fields ++ List(score.toString) mkString ","
    }

    csvRdd.saveAsTextFile(location)
  }

  def saveDb(rdd: RDD[(Row, Double)]) : SQLTransaction[Unit] = withSqlContext map { context =>
    import context.implicits._
    import Implicits.config.pg

    val newDf = rdd.map {
      case (r, score) =>
        val id = r.getAs[Int]("article_id")
        (id, score)
    }.toDF()

    newDf.write.mode(SaveMode.Overwrite).jdbc(pg.url, "article_ranking", props)
  }

  def getOutputFile(rootDir: String) = {
    val timestamp: Long = new java.util.Date().getTime()
    new java.io.File(rootDir, s"DataframeOut-$timestamp.csv").getAbsolutePath()
  }

  Fingerprint.rankJob.flatMap(saveDb).go
}
