package co.insilica.sysrev
package indexing

import org.apache.spark.ml.feature.{HashingTF, IDF, Tokenizer}
import org.apache.spark.sql.{DataFrame, Row}

import scalaz._
import Scalaz._
import Implicits.config.pg

object Fingerprint {
  import spark.readers._

  def sysrevTfidf: SQLTransaction[Array[Row]] = withSqlContext map { context =>
    import context.implicits._

    //    select primary_title, secondary_title, abstract, answer
    //    from article
    //      left join article_criteria using (article_Id)
    //    left join criteria using (criteria_id)
    //    where name = 'overall include' or name is null

    val opts = Map(
      "url" -> s"jdbc:postgresql://${pg.hostPort}/${pg.dbName}",
      "driver" -> "org.postgresql.Driver",
      "user" -> pg.name,
      "password" -> pg.password
    )

    val articleDf = context.read
      .format("jdbc")
      .options(opts + ("dbtable" -> "article"))
      .load()


    val articleCriteriaDf = context.read.format("jdbc").options(opts + ("dbtable" -> "article_criteria")).load()
    val criteriaDf = context.read.format("jdbc").options(opts + ("dbtable" -> "criteria")).load()
    val keywordDf = context.read.format("jdbc").options(opts + ("dbtable" -> "keyword")).load()
    val articleKeywordDf = context.read.format("jdbc").options(opts + ("dbtable" -> "article_keyword")).load()

    val qdf = articleCriteriaDf
              .join(articleDf, usingColumn = "article_id")
              .join(criteriaDf, usingColumn = "criteria_id")
              .filter("""name = 'overall include' or name is null""")

    val tokenizer = new Tokenizer().setInputCol("abstract").setOutputCol("words")
    val wordsData = tokenizer.transform(qdf)
    val hashingTF = new HashingTF()
      .setInputCol("words").setOutputCol("rawFeatures").setNumFeatures(100)
    val featurizedData = hashingTF.transform(wordsData)
    val idf = new IDF().setInputCol("rawFeatures").setOutputCol("features")
    val idfModel = idf.fit(featurizedData)
    val rescaledData = idfModel.transform(featurizedData)

    rescaledData.select("features", "answer").take(50)
  }
}


object Test extends App{
  import spark.Implicits.local._
  import spark.readers._

  Fingerprint.sysrevTfidf.go foreach println
}
