package co.insilica.sysrev
package relationalImporter

object Types{
  case class WithAnyId[I, T](id: I, t: T)

  type KeywordId = Int
  type WithKeywordId[T] = WithAnyId[KeywordId, T]

  type ArticleId = Int
  type WithArticleId[T] = WithAnyId[ArticleId, T]
}