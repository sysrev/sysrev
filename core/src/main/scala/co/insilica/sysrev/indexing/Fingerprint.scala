package co.insilica.sysrev
package indexing

import org.apache.spark.ml.Transformer
import org.apache.spark.ml.feature.{VectorAssembler, HashingTF, IDF, Tokenizer}
import org.apache.spark.sql.{DataFrame, Row}

import spark.readers.dataFrameBuilder

import scalaz._
import Scalaz._
import Implicits.config.pg

object Fingerprint {
  import spark.readers._
  import spark.alg._

  def sysrevTfidf: SQLTransaction[Array[Row]] = withSqlContext map { context =>
    import context.implicits._

    val makeDf = dataFrameBuilder(context)

    val articleDf = makeDf("article")
    val articleCriteriaDf = makeDf("article_criteria")
    val criteriaDf = makeDf("criteria")
    val keywordDf = makeDf("keyword")
    val articleKeywordDf = makeDf("article_keyword")

    // Emulating a query like this:
    //    select primary_title, secondary_title, abstract, answer
    //    from article
    //      left join article_criteria using (article_Id)
    //    left join criteria using (criteria_id)
    //    where name = 'overall include' or name is null

    // Assemble dataframe by joining and all the above and filtering
    // so we only have our target answer.
    val qdf : DataFrame = articleCriteriaDf
              .join(articleDf, usingColumn = "article_id")
              .join(criteriaDf, usingColumn = "criteria_id")
              .join(articleKeywordDf, usingColumn = "article_id")
              .join(keywordDf, usingColumn = "keyword_id")
              .filter("""name = 'overall include' or name is null""")


    // Set up transformer pipeline, to be applied in order.
    val transformers : List[DataFrame => DataFrame] = List(
      tokenize(
        "abstract" -> "abstract words",
        "primary_title" -> "title1 words",
        "secondary_title" -> "title2 words"
      ),
      applyTfidf("title1 words" -> "title1 words tfidf", 10),
      applyTfidf("abstract words" -> "abstract words tfidf", 100),
      applyTfidf("title2 words" -> "title2 words tfidf", 10),
      new VectorAssembler()
        .setInputCols(Array("abstract words tfidf", "title1 words tfidf", "title2 words tfidf"))
        .setOutputCol("features").transform
    )


    transformers.foldLeft(qdf)(app).select("features", "answer").take(100)
  }
}


object Test extends App{
  import spark.Implicits.local._
  import spark.readers._

  Fingerprint.sysrevTfidf.go foreach println
}
