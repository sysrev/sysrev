package co.insilica.sysrev.data

/**
  * Helpers for results from queries.
  */
object QueryOps {


  /**
    * From a result of a join, collapse join table into List of category elements. Assumes list is
    * sorted by some category Id, such that all items of the category will merge properly.
    *
    * @param when A merge / skip predicate
    * @param merger How to merge an element into its predecessor
    * @tparam B The result type of the merged table.
    * @return a list of the category elements.
    */
  def collapse[A, B](as: List[A])(when: (A, A) => Boolean)(merger: (Option[B], A) => B): List[B] = {
    as match {
      case Nil => Nil
      case car :: Nil => List(merger(None, car))
      case _ => {
        val windowed = as.sliding(2).map(xs => (xs.head, xs.tail.head)).toList
        windowed.foldLeft(List(merger(None, windowed.head._1))) {
          case (acc, (left, right)) if when(left, right) => merger(Some(acc.head), right) :: acc.tail
          case (acc, (left, right)) => merger(None, right) :: acc
        }
      }
    }
  }
}