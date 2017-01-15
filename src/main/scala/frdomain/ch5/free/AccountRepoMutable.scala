package frdomain.ch5
package free

import scala.collection.mutable.{ Map => MMap }
import scalaz._
import Scalaz._
import scalaz.concurrent.Task
import Task.{now, fail}

trait AccountRepoInterpreter[M[_]] {
  def apply[A](action: AccountRepo[A]): M[A]
}
  
/**
 * Basic interpreter that uses a global mutable Map to store the state
 * of computation
 */
case class AccountRepoMutableInterpreter() extends AccountRepoInterpreter[Task] {
  val table: MMap[String, Account] = MMap.empty[String, Account]

  val step: AccountRepoF ~> Task = new (AccountRepoF ~> Task) {
    override def apply[A](fa: AccountRepoF[A]): Task[A] = fa match {
      case Query(no) =>
        table.get(no).map { a => now(a) }
                     .getOrElse { fail(new RuntimeException(s"Account no $no not found")) }

      case Store(account) => now(table += ((account.no, account))).void
      case Delete(no) => now(table -= no).void
    }
  }

  /**
   * Turns the AccountRepo script into a `Task` that executes it in a mutable setting
   */
  def apply[A](action: AccountRepo[A]): Task[A] = action.foldMap(step)
}

case class AccountRepoShowInterpreter() {

  type ListState[A] = State[List[String], A]
  val step: AccountRepoF ~> ListState = new (AccountRepoF ~> ListState) {
    private def show(s: String): ListState[Unit] = State(l => (l ++ List(s), ()))
    override def apply[A](fa: AccountRepoF[A]): ListState[A] = fa match {
      case Query(no) => show(s"Query for $no").map(_ => Account(no, ""))
      case Store(account) => show(s"Storing $account")
      case Delete(no) => show(s"Deleting $no")
    }
  }

  def interpret[A](script: AccountRepo[A], ls: List[String]): List[String] =
    script.foldMap(step).exec(ls)

}

object AccountRepoState {
  type AccountMap = Map[String, Account]
  type Err[A] = Error \/ A
  type AccountState[A] = StateT[Err, AccountMap, A]
}

import AccountRepoState._
import StateT._

case class AccountRepoStateInterpreter() extends AccountRepoInterpreter[AccountState] {

  val step: AccountRepoF ~> AccountState = new (AccountRepoF ~> AccountState) {
    override def apply[A](fa: AccountRepoF[A]): AccountState[A] = fa match {
      case Query(no) => StateT[Err, AccountMap, A]((s: AccountMap) => {
        s.get(no).map(a => (s, a).right[Error]).getOrElse((new Error(s"Account no $no not found")).left[(AccountMap, A)])
      })
      case Store(account) => StateT[Err, AccountMap, A]((s: AccountMap) => {
        (s + (account.no -> account), ()).right[Error]
      })
      case Delete(no) => StateT[Err, AccountMap, A]((s: AccountMap) => {
        (s - no, ()).right[Error]
      })
    }
  }

  def apply[A](action: AccountRepo[A]): AccountState[A] = action.foldMap(step)
}