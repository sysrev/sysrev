package co.insilica.sysrev

import co.insilica.dataProvider.mongo._
import reactivemongo.bson._

import scalaz._
import Scalaz._


case class
AuthorList(names: List[String])
case class Contributors(authors: Option[AuthorList]){
  def names : List[String] = authors.map(_.names).getOrElse(Nil)
}
case class PubDates(date: Option[String])
case class Dates(year: Option[Int], pubdate: Option[PubDates]){
  def date: Option[String] = pubdate.flatMap(_.date)
}

case class UrlList(urls: List[String])
case class RelatedUrls(us: Option[UrlList]){
  def urls : List[String] = us.map(_.urls).getOrElse(Nil)
}

case class PdfUrls(us: Option[UrlList]){
  import PdfUrls._
  def urls: List[String] = us.map(_.urls).getOrElse(Nil)

  def pdfidFileNames: List[(String, String)] = urls.collect{
    case PdfUrl(id, filename) => (id, filename)
  }

  def pdfIds = pdfidFileNames.map(_._1)
}

object PdfUrls{
  val PdfUrl = """internal-pdf://([^/]+)/(.+)""".r
}

case class Titles(title: String, secondaryTitle: Option[String])
case class SysRev(titles: Titles, docabstract: Option[String], keywords: List[String])
case class Article(
  sysRev: SysRev,
  authors: List[String],
  work_type: Option[String],
  remoteDatabaseName: Option[String],
  year: Option[Int],
  pubDates: Option[String],
  urls: List[String],
  documentIds: List[String]
){
  def primaryTitle: String = sysRev.titles.title
  def secondaryTitle: Option[String] = sysRev.titles.secondaryTitle
}


object Types {
  def makeReader[T](f : BSONDocument => T): BSONDocumentReader[T] = new BSONDocumentReader[T] {
    def read(bson: BSONDocument): T = f(bson)
  }

  def makeWriter[T](f: T => BSONDocument) : BSONDocumentWriter[T] = new BSONDocumentWriter[T] {
    def write(t: T): BSONDocument = f(t)
  }

// Should be able to make this generic
//  def getList[T, V <: BSONValue](bson: BSONDocument)(name : String)(implicit reader : BSONReader[V, T]): List[T] =
//    bson.getAs[List[T]](name).orElse(bson.getAs[T](name).map(List apply _)).getOrElse(Nil)

  def getList(bson: BSONDocument)(name : String): List[String] =
      bson.getAs[List[String]](name).orElse(bson.getAs[String](name).map(List apply _)).getOrElse(Nil)

  implicit val titleReader = makeReader { bson =>
    Titles(
      bson.getAs[String]("title").get,
      bson.getAs[String]("secondary-title")
    )
  }

  implicit val sysRevReader = makeReader { bson =>
    SysRev(
      bson.getAs[Titles]("titles").get,
      bson.getAs[String]("abstract"),
      bson.getAs[BSONDocument]("keywords") flatMap (_.getAs[List[String]]("keyword")) getOrElse (List[String]())
    )
  }

  // TODO: Handle single url here correctly.
  implicit val urlsListReader = makeReader { bson => UrlList(getList(bson)("url")) }
  implicit val relatedUrlsReader = makeReader { bson => RelatedUrls(bson.getAs[UrlList]("related-urls")) }
  implicit val pdfUrlsReader = makeReader { bson => PdfUrls(bson.getAs[UrlList]("pdf-urls")) }


  implicit val sysRevProjector = new Projector[SysRev]{ def apply() = BSONDocument("abstract" -> 1, "keywords" -> 1, "titles" -> 1)}

  implicit val authorListReader = makeReader(bson => AuthorList(getList(bson)("author")))

  implicit val contributorsReader = makeReader(b => Contributors(b.getAs[AuthorList]("authors")(authorListReader)))

  implicit val pubDatesReader = makeReader(b => PubDates(b.getAs[String]("date")))

  implicit val datesReader = makeReader { bson =>
    Dates(
      bson.getAs[Int]("year"),
      bson.getAs[PubDates]("pub-dates")
    )
  }

  implicit val articleReader = makeReader { bson =>
    val ds = bson.getAs[Dates]("dates")

    Article(
      bson.as[SysRev],
      bson.getAs[Contributors]("contributors").map(_.names).getOrElse(Nil),
      bson.getAs[String]("work-type"),
      bson.getAs[String]("remote-database-name"),
      ds.flatMap(_.year),
      ds.flatMap(_.date),
      bson.getAs[RelatedUrls]("urls").map(_.urls).getOrElse(Nil),
      bson.getAs[PdfUrls]("urls").map(_.pdfIds).getOrElse(Nil)
    )
  }

  implicit val articleProjector : Projector[Article] = new Projector[Article]{
    def apply() : BSONDocument = sysRevProjector() ++ BSONDocument(
      "contributors" -> 1,
      "work-type" -> 1,
      "remote-database-name" -> 1,
      "dates" -> 1,
      "urls" -> 1,
      "keywords" -> 1
    )
  }
}