package co.insilica.sysrev
package spark

import org.apache.spark.ml.feature.{Tokenizer, IDF, HashingTF}
import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{Row, DataFrame}
import scalaz._
import Scalaz._


object alg{
  /**
    * Apply TFIDF to the dataframe, using the given columns
    *
    * @param inOut in column and out column to apply tfidf with
    * @param numFeatures number of features to use in hashing TF
    * @param df dataframe to apply to.
    * @return New dataframe with new column added containing the tfidf result
    */
  def applyTfidf(inOut: (String, String), numFeatures: Int)(df: DataFrame) : DataFrame = {
    val (in, out) = inOut
    val tfcol = s"${in}_df"
    val hashingTF = new HashingTF().setInputCol(in).setOutputCol(tfcol).setNumFeatures(numFeatures)
    val idf = new IDF().setInputCol(tfcol).setOutputCol(out)
    val featurized = hashingTF.transform(df)
    idf.fit(featurized).transform(featurized)
  }

  /**
    * Use the tokenizer to make a new dataframe with a tokenized column
    *
    * @param in column to tokenize
    * @param out new column for array of tokens
    * @return new [[DataFrame]] with added column of tokens
    */
  def tokenize(in: String, out: String) : DataFrame => DataFrame =
    new Tokenizer().setInputCol(in).setOutputCol(out).transform

  /**
    * Apply the tokenizer to any number of columns.
    *
    * @param cols Columns in -> out tuples
    * @return a new data frame with tokenized columns added
    */
  def tokenize(cols: (String, String)*): DataFrame => DataFrame = df =>
    cols.map{
      case (in, out) => tokenize(in, out)
    }.foldLeft(df)(_ |> _)


  /**
    * Transpose an RDD....
    * https://stackoverflow.com/questions/29390717/how-to-transpose-an-rdd-in-spark
    */
  def transpose[A](rdd: RDD[Seq[A]]): RDD[Seq[A]] = {
    val byColumnAndRow = rdd.zipWithIndex.flatMap {
      case (row, rowIndex) => row.zipWithIndex.map {
        case (number, columnIndex) => columnIndex -> ((rowIndex, number))
      }
    }
    // Build up the transposed matrix. Group and sort by column index first.
    val byColumn = byColumnAndRow.groupByKey.sortByKey().values
    // Then sort by row index.
    byColumn.map {
      indexedRow => indexedRow.toSeq.sortBy(_._1).map(_._2)
    }
  }

  def vectorify(rdd: RDD[Seq[Double]]): RDD[Types.SparkVector] = {
    rdd.map(xs => Vectors.dense(xs.toArray))
  }
}
