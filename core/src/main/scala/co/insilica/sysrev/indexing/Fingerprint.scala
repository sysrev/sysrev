package co.insilica.sysrev
package indexing

import org.apache.spark.ml.feature.{HashingTF, IDF, Tokenizer}
import org.apache.spark.sql.{DataFrame, Row}

import scalaz._
import Scalaz._

object Fingerprint {
  import spark.readers._

  def sysrevTfidf: SQLTransaction[Array[Row]] = withSqlContext map { context =>
    import context.implicits._

    val sentenceData = context.createDataFrame(Seq(
      (0, "Hi I heard about Spark"),
      (0, "I wish Java could use case classes"),
      (1, "Logistic regression models are neat")
    )).toDF("label", "sentence")

    val tokenizer = new Tokenizer().setInputCol("sentence").setOutputCol("words")
    val wordsData = tokenizer.transform(sentenceData)
    val hashingTF = new HashingTF()
      .setInputCol("words").setOutputCol("rawFeatures").setNumFeatures(20)
    val featurizedData = hashingTF.transform(wordsData)
    val idf = new IDF().setInputCol("rawFeatures").setOutputCol("features")
    val idfModel = idf.fit(featurizedData)
    val rescaledData = idfModel.transform(featurizedData)

    rescaledData.select("features", "label").take(10)
  }
}


object Test extends App{
  import spark.Implicits.local._
  import spark.readers._

  Fingerprint.sysrevTfidf.go foreach println
}
