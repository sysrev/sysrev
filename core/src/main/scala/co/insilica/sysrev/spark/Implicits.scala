package co.insilica.sysrev.spark

import org.apache.spark.sql.SQLContext
import org.apache.spark.{SparkContext, SparkConf}




object Implicits{
  trait build{
    implicit def getContext(implicit conf: SparkConf) : SparkContext = new SparkContext(conf)
    implicit def getSqlContext(implicit conf: SparkConf) : SQLContext = new SQLContext(getContext)
  }

  object local extends build {
    implicit val conf = new SparkConf()
      .setAppName("SparkMe Application")
      .setMaster("local[*]")  // local mode
  }

  object tunnel extends build {
    implicit val conf = new SparkConf()
      .setAppName("try it remotely")
      .setMaster("spark://insilica-ws-1:7077")  // tunnel to master.
  }

  import org.apache.spark.deploy.SparkSubmit

}

