package co.insilica.sysrev
package spark

import org.apache.spark.ml.feature.{Tokenizer, IDF, HashingTF}
import org.apache.spark.sql.DataFrame

object alg{
  // For convenience, special case of folding over a list of unary functions
  def app[A](a: A, f: A => A): A = f(a)

  /**
    * Apply TFIDF to the dataframe, using the given columns
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
    * @param in column to tokenize
    * @param out new column for array of tokens
    * @return new [[DataFrame]] with added column of tokens
    */
  def tokenize(in: String, out: String) : DataFrame => DataFrame =
    new Tokenizer().setInputCol(in).setOutputCol(out).transform

  /**
    * Apply the tokenizer to any number of columns.
    * @param cols Columns in -> out tuples
    * @return a new data frame with tokenized columns added
    */
  def tokenize(cols: (String, String)*): DataFrame => DataFrame = df =>
    cols.map{
      case (in, out) => tokenize(in, out)
    }.foldLeft(df)(app)

}