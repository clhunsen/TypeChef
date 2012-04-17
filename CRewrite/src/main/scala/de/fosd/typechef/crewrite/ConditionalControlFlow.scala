package de.fosd.typechef.crewrite


import de.fosd.typechef.conditional._
import de.fosd.typechef.featureexpr.FeatureExpr
import de.fosd.typechef.parser.c._
import org.kiama.attribution.AttributionBase
import java.util.IdentityHashMap

// implements conditional control flow (cfg) on top of the typechef
// infrastructure
// at first sight the implementation of succ with a lot of private
// function seems overly complicated; however the structure allows
// also to implement pred
// consider the following points:

// the function definition an ast belongs to serves as the entry
// and exit node of the cfg, because we do not have special ast
// nodes for that, or we store everything in a ccfg itself with
// special nodes for entry and exit such as
// [1] http://soot.googlecode.com/svn/DUA_Forensis/src/dua/method/CFG.java

// normally pred succ are the same except for the following cases:
// 1. forward jumps using gotos
// 2. code in switch body that does not belong to a case block, but that has a label and
//    can be reached otherwise, e.g., switch (x) { l1: <code> case 0: .... }
// 3. infinite for loops without break or return statements in them, e.g.,
//    for (;;) { <code without break or return> }
//    this way we do not have any handle to jump into the for block from
//    its successors

// for more information:
// iso/iec 9899 standard; committee draft
// [2] http://www.open-std.org/jtc1/sc22/wg14/www/docs/n1124.pdf

// TODO support for (expr) ? (expr) : (expr);
// TODO analysis static gotos should have a label (if more labels must be unique according to feature expresssions)
// TODO analysis dynamic gotos should have a label
// TODO analysis: The expression of each case label shall be an integer constant expression and no two of
//                the case constant expressions in the same switch statement shall have the same value
//                after conversion.
// TODO analysis: There may be at most one default label in a switch statement.
// TODO analysis: we can continue this list only by looking at constrains in [2]


class CCFGCache {
  private val cache: IdentityHashMap[Any, List[AST]] = new IdentityHashMap[Any, List[AST]]()

  def update(k: Any, v: List[AST]) {
    cache.put(k, v)
  }

  def lookup(k: Any): Option[List[AST]] = {
    val v = cache.get(k)
    if (v != null) Some(v)
    else None
  }
}

trait ConditionalControlFlow extends CASTEnv with ASTNavigation {

  private implicit def optList2ASTList(l: List[Opt[AST]]) = l.map(_.entry)

  private implicit def opt2AST(s: Opt[AST]) = s.entry

  private implicit def conditional2AST(c: Conditional[_]) = childAST(c)

  private val predCCFGCache = new CCFGCache()
  private val succCCFGCache = new CCFGCache()

  type IfdefBlock = List[AST]
  type IfdefBlocks = List[List[AST]]

  // determines predecessor of a given element
  // results are cached for secondary evaluation
  def pred(a: Any, env: ASTEnv): List[AST] = {
    predCCFGCache.lookup(a) match {
      case Some(v) => v
      case None => {
        var oldres: List[AST] = List()
        var newres: List[AST] = predHelper(a, env)
        var changed = true

        while (changed) {
          changed = false
          oldres = newres
          newres = List()

          for (oldelem <- oldres) {
            var add2newres = List[AST]()
            oldelem match {
              case _: ReturnStatement if (!a.isInstanceOf[FunctionDef]) => changed = true; add2newres = List()

              // a break statement shall appear only in or as a switch body or loop body
              // a break statement terminates execution of the smallest enclosing switch or
              // iteration statement (see standard [2])
              // therefore all predecessors of a belong to a different switch/loop;
              // otherwise we filter them
              case b: BreakStatement => {
                val a2b = findPriorASTElem2BreakStatement(a.asInstanceOf[AnyRef], env)
                val b2b = findPriorASTElem2BreakStatement(b.asInstanceOf[AnyRef], env)

                if (a2b.isEmpty && b2b.isDefined) add2newres = List(b)
                else if (a2b.isDefined && b2b.isDefined && a2b.get.ne(b2b.get)) add2newres = List(b)
                else add2newres = List()
              }
              // a continue statement shall appear only in a loop body
              // a continue statement causes a jump to the loop-continuation portion
              // of the smallest enclosing iteration statement
              case c: ContinueStatement => {
                val a2c = findPriorASTElem2ContinueStatement(a.asInstanceOf[AnyRef], env)
                val b2c = findPriorASTElem2ContinueStatement(c.asInstanceOf[AnyRef], env)

                if (a2c.isDefined && b2c.isDefined && a2c.get.eq(b2c.get)) {
                  a2c.get match {
                    // TODO more complex expr2 might be None
                    case WhileStatement(expr, _) if (isPartOfAST(a, expr)) => add2newres = List(c)
                    case DoStatement(expr, _) if (isPartOfAST(a, expr)) => add2newres = List(c)
                    case _ => add2newres = List()
                  }
                } else add2newres = List()
              }
              case _ => {
                add2newres = rollUp(oldelem, env)
                if (!(add2newres.size == 1 && add2newres.head.eq(oldelem))) changed = true
              }
            }

            // add only elements that are not in newres so far
            // add them add the end to keep the order of the elements
            for (addnew <- add2newres)
              if (newres.map(_.eq(addnew)).foldLeft(false)(_ || _).unary_!) newres = newres ++ List(addnew)
          }
        }
        predCCFGCache.update(a, newres)
        newres
      }
    }
  }
  
  private def isPartOfAST(e: Any, t: Any): Boolean = {
    t match {
      case _ if (e.asInstanceOf[AnyRef].eq(t.asInstanceOf[AnyRef])) => true
      case l: List[_] => l.map(isPartOfAST(e, _)).fold(false)(_ || _)
      case x: Product => x.productIterator.toList.map(isPartOfAST(e, _)).fold(false)(_ || _)
      case _ => false
    }
  }

  def predHelper(a: Any, env: ASTEnv): List[AST] = {

    // helper method to handle a switch, if we come from a case or a default statement
    def handleSwitch(t: AST) = {
      val prior_switch = findPriorASTElem[SwitchStatement](t, env)
      assert(prior_switch.isDefined, "default or case statements should always occur withing a switch definition")
      prior_switch.get match {
        case SwitchStatement(expr, _) => getCondExprPred(expr, env) ++ getStmtPred(t, env)
      }
    }

    a match {
      case t: CaseStatement => handleSwitch(t)
      case t: DefaultStatement => handleSwitch(t)

      case t@LabelStatement(Id(n), _) => {
        findPriorASTElem[FunctionDef](t, env) match {
          case None => assert(false, "label statements should always occur within a function definition"); List()
          case Some(f) => {
            val l_gotos = gotoLookup(f, n, env)
            val l_preds = getStmtPred(t, env)
            l_gotos ++ l_preds
          }
        }
      }

      case o: Opt[_] => predHelper(childAST(o), env)
      case c: Conditional[_] => predHelper(childAST(c), env)

      case f@FunctionDef(_, _, _, stmt) => predHelper(childAST(stmt), env) ++ filterASTElems[ReturnStatement](f)
      case CompoundStatement(innerStatements) => getCompoundPred(innerStatements, env)

      case s: Statement => getStmtPred(s, env)
      case _ => nestedPred(a, env)
    }
  }

  def succ(a: Any, env: ASTEnv): List[AST] = {
    succCCFGCache.lookup(a) match {
      case Some(v) => v
      case None => {
        var oldres: List[AST] = List()
        var newres: List[AST] = succHelper(a, env)
        var changed = true

        while (changed) {
          changed = false
          oldres = newres
          newres = List()
          for (oldelem <- oldres) {
            var add2newres: List[AST] = List()
            oldelem match {
              case _: IfStatement => changed = true; add2newres = succHelper(oldelem, env)
              case _: ElifStatement => changed = true; add2newres = succHelper(oldelem, env)
              case _: SwitchStatement => changed = true; add2newres = succHelper(oldelem, env)
              case _: CompoundStatement => changed = true; add2newres = succHelper(oldelem, env)
              case _: DoStatement => changed = true; add2newres = succHelper(oldelem, env)
              case _: WhileStatement => changed = true; add2newres = succHelper(oldelem, env)
              case _: ForStatement => changed = true; add2newres = succHelper(oldelem, env)
              case _: DefaultStatement => changed = true; add2newres = succHelper(oldelem, env)
              case _ if (!oldelem.eq(a.asInstanceOf[AnyRef])) => add2newres = List(oldelem)
              case _ =>
            }

            // add only elements that are not in newres so far
            // add them add the end to keep the order of the elements
            for (addnew <- add2newres)
              if (newres.map(_.eq(addnew)).foldLeft(false)(_ || _).unary_!) newres = newres ++ List(addnew)
          }
        }
        succCCFGCache.update(a, newres)
        newres
      }
    }
  }

  private def succHelper(a: Any, env: ASTEnv): List[AST] = {
    a match {
      // ENTRY element
      case f@FunctionDef(_, _, _, stmt) => succHelper(stmt, env)

      // EXIT element
      case t@ReturnStatement(_) => {
        findPriorASTElem[FunctionDef](t, env) match {
          case None => assert(false, "return statement should always occur within a function statement"); List()
          case Some(f) => List(f)
        }
      }

      case t@CompoundStatement(l) => getCompoundSucc(l, env)

      case o: Opt[_] => succHelper(o.entry, env)
      case t: Conditional[_] => succHelper(childAST(t), env)

      // loop statements
      case t@ForStatement(expr1, expr2, expr3, s) => {
        if (expr1.isDefined) List(expr1.get)
        else if (expr2.isDefined) List(expr2.get)
        else getCondStmtSucc(t, childAST(s), env)
      }
      case WhileStatement(expr, _) => List(expr)
      case t@DoStatement(_, s) => getCondStmtSucc(t, childAST(s), env)

      // conditional statements
      case t@IfStatement(condition, _, _, _) => List(condition)
      case t@ElifStatement(c, _) => List(c)
      case SwitchStatement(c, _) => List(c)

      case t@BreakStatement() => {
        val e2b = findPriorASTElem2BreakStatement(t, env)
        assert(e2b.isDefined, "break statement should always occur within a for, do-while, while, or switch statement")
        getStmtSucc(e2b.get, env)
      }
      case t@ContinueStatement() => {
        val e2c = findPriorASTElem2ContinueStatement(t, env)
        assert(e2c.isDefined, "continue statement should always occur within a for, do-while, or while statement")
        e2c.get match {
          case t@ForStatement(_, break, inc, b) => {
            if (inc.isDefined) List(inc.get)
            else if (break.isDefined) List(break.get)
            else getCondStmtSucc(t, b, env)
          }
          case WhileStatement(c, _) => List(c)
          case DoStatement(c, _) => List(c)
          case _ => List()
        }
      }
      case t@GotoStatement(Id(l)) => {
        findPriorASTElem[FunctionDef](t, env) match {
          case None => assert(false, "goto statement should always occur within a function definition"); List()
          case Some(f) => {
            val l_list = labelLookup(f, l, env)
            if (l_list.isEmpty) getStmtSucc(t, env)
            else l_list
          }
        }
      }
      // in case we have an indirect goto dispatch all goto statements
      // within the function (this is our invariant) are possible targets of this goto
      // so fetch the function statement and filter for all label statements
      case t@GotoStatement(PointerDerefExpr(_)) => {
        findPriorASTElem[FunctionDef](t, env) match {
          case None => assert(false, "goto statement should always occur within a function definition"); List()
          case Some(f) => {
            val l_list = filterASTElems[LabelStatement](f)
            if (l_list.isEmpty) getStmtSucc(t, env)
            else l_list
          }
        }
      }

      case t@CaseStatement(_, s) => {
        if (s.isDefined) getCondStmtSucc(t, s.get, env)
        else getStmtSucc(t, env)
      }

      case t@DefaultStatement(s) => {
        if (s.isDefined) getCondStmtSucc(t, s.get, env)
        else getStmtSucc(t, env)
      }

      case t: Statement => getStmtSucc(t, env)
      case t => nestedSucc(t, env)
    }
  }

  private def iterateChildren(a: Any, l: String, env: ASTEnv, op: (Any, String, ASTEnv) => List[AST]): List[AST] = {
    env.children(a).map(
      x => x match {
        case e: AST => op(e, l, env)
        case Opt(_, entry) => op(entry, l, env)
        case ls: List[_] => ls.flatMap(op(_, l, env))
        case _ => List()
      }
    ).foldLeft(List[AST]())(_ ++ _)
  }

  private def labelLookup(a: Any, l: String, env: ASTEnv): List[AST] = {
    a match {
      case e@LabelStatement(Id(n), _) if (n == l) => List(e) ++ iterateChildren(e, l, env, labelLookup)
      case e: AST => iterateChildren(e, l, env, labelLookup)
      case o: Opt[_] => iterateChildren(o, l, env, labelLookup)
    }
  }

  // lookup all goto with matching ids or those using indirect goto dispatch
  // indirect goto dispatch are possible candidates for this label because
  // evaluating the expression of the goto might lead to the given label
  private def gotoLookup(a: Any, l: String, env: ASTEnv): List[AST] = {
    a match {
      case e@GotoStatement(Id(n)) if (n == l) => List(e)
      case e@GotoStatement(PointerDerefExpr(_)) => List(e)
      case e: AST => iterateChildren(e, l, env, gotoLookup)
      case o: Opt[_] => iterateChildren(o, l, env, gotoLookup)
    }
  }

  private def getCondStmtSucc(p: AST, c: AST, env: ASTEnv) = {
    c match {
      case CompoundStatement(l) => if (l.isEmpty) List(p) else getCompoundSucc(l, env)
      case s: Statement => List(s)
    }
  }

  private def getCondStmtPred(a: AST, c: AST, env: ASTEnv) = {
    c match {
      case CompoundStatement(l) => getCompoundPred(l, env)
      case s: Statement => List(s)
    }
  }

  private def getCondExprSucc(e: Expr, env: ASTEnv) = {
    e match {
      case CompoundStatementExpr(CompoundStatement(innerStatements)) => getCompoundSucc(innerStatements, env)
      case _ => List(e)
    }
  }

  private def getCondExprPred(e: Expr, env: ASTEnv) = {
    e match {
      case CompoundStatementExpr(CompoundStatement(innerStatements)) => getCompoundPred(innerStatements, env)
      case _ => List(e)
    }
  }

  // handling of successor determination of nested structures, such as for, while, ... and next element in a list
  // of statements
  private def nestedSucc(nested_ast_elem: Any, env: ASTEnv): List[AST] = {
    val surrounding_parent = parentAST(nested_ast_elem, env)
    surrounding_parent match {
      case t@ForStatement(Some(expr1), expr2, _, s) if expr1.eq(nested_ast_elem.asInstanceOf[AnyRef]) =>
        if (expr2.isDefined) getCondExprSucc(expr2.get, env)
        else getCondStmtSucc(t, s, env)
      case t@ForStatement(_, Some(expr2), _, s) if expr2.eq(nested_ast_elem.asInstanceOf[AnyRef]) =>
        getStmtSucc(t, env) ++ getCondStmtSucc(t, s, env)
      case t@ForStatement(_, expr2, Some(expr3), s) if expr3.eq(nested_ast_elem.asInstanceOf[AnyRef]) =>
        if (expr2.isDefined) getCondExprSucc(expr2.get, env)
        else getCondStmtSucc(t, s, env)
      case t@ForStatement(_, expr2, expr3, s) if childAST(s).eq(nested_ast_elem.asInstanceOf[AnyRef]) => {
        if (expr3.isDefined) getCondExprSucc(expr3.get, env)
        else if (expr2.isDefined) getCondExprSucc(expr2.get, env)
        else getCondStmtSucc(t, childAST(s), env)
      }
      case t@WhileStatement(expr, s) if expr.eq(nested_ast_elem.asInstanceOf[AnyRef]) =>
        getCondStmtSucc(t, s, env) ++ getStmtSucc(t, env)
      case t@DoStatement(e, b) if e.eq(nested_ast_elem.asInstanceOf[AnyRef]) =>
        getCondStmtSucc(t, b, env) ++ getStmtSucc(t, env)
      case t@IfStatement(e, tb, elif, el) if e.eq(nested_ast_elem.asInstanceOf[AnyRef]) => {
        var res = getCondStmtSucc(t, tb, env)
        if (!elif.isEmpty) res = res ++ getCompoundSucc(elif, env)
        if (elif.isEmpty && el.isDefined) res = res ++ getCondStmtSucc(t, el.get, env)
        if (elif.isEmpty && !el.isDefined) res = res ++ getStmtSucc(t, env)
        res
      }

      // either go to next ElifStatement, ElseBranch, or next statement of the surrounding IfStatement
      // filtering is necessary, as else branches are not considered by getSuccSameLevel
      case t@ElifStatement(condition, thenBranch) if condition.eq(nested_ast_elem.asInstanceOf[AnyRef]) => {
        var res = getStmtSucc(t, env)
        if (res.filter(_.isInstanceOf[ElifStatement]).isEmpty) {
          env.parent(env.parent(t)) match {
            case tp@IfStatement(_, _, _, None) => res = getStmtSucc(tp, env)
            case IfStatement(_, _, _, Some(elseBranch)) => res = List(elseBranch)
          }
        }
        res ++ getCondStmtSucc(t, thenBranch, env)
      }

      // the switch statement behaves like a dynamic goto statement;
      // based on the expression we jump to one of the case statements or default statements
      // after the jump the case/default statements do not matter anymore
      // when hitting a break statement, we jump to the end of the switch
      case t@SwitchStatement(expr, s) => {
        var res: List[AST] = List()
        if (expr.eq(nested_ast_elem.asInstanceOf[AnyRef])) {
          res = filterCaseStatements(s, env)
          val dcase = filterDefaultStatements(s, env)

          if (dcase.isEmpty) res = res ++ getStmtSucc(t, env)
          else res = res ++ dcase
        }
        res
      }
      case _ => List()
    }
  }

  // handling of predecessor determination of nested structures, such as for, while, ... and previous element in a list
  // of statements
  private def nestedPred(nested_ast_elem: Any, env: ASTEnv): List[AST] = {
    val surrounding_parent = parentAST(nested_ast_elem, env)
    surrounding_parent match {
      // loop statements
      case t@ForStatement(Some(expr1), _, _, _) if expr1.eq(nested_ast_elem.asInstanceOf[AnyRef]) => getStmtPred(t, env)
      case t@ForStatement(_, _, Some(expr3), s) if expr3.eq(nested_ast_elem.asInstanceOf[AnyRef]) =>
        getCondStmtPred(t, s, env)
      case t@ForStatement(expr1, Some(expr2), expr3, s) => {
        var res = List[AST]()
        if (expr1.isDefined) res = getCondExprPred(expr1.get, env) ++ res
        else res = getStmtPred(t, env) ++ res
        if (expr3.isDefined) res = getCondExprPred(expr3.get, env) ++ res
        else res = res ++ getCondStmtPred(t, s, env)
        res
      }

      case t@WhileStatement(expr, s) if expr.eq(nested_ast_elem.asInstanceOf[AnyRef]) =>
        getStmtPred(t, env) ++ getCondStmtPred(t, s, env) ++ filterContinueStatements(s, env)
      case t@DoStatement(expr, s) if expr.eq(nested_ast_elem.asInstanceOf[AnyRef]) =>
        getCondStmtPred(t, s, env) ++ filterContinueStatements(s, env)

      // conditional statements
      // we are in condition, so the IfStatement itself is the result
      // we are in thenBranch, so condition is the result
      // we are in elseBranch, so either elifs + condition or condition only is the result
      // otherwise
      case t@IfStatement(condition, thenBranch, elifs, elseBranch) => {
        if (condition.eq(nested_ast_elem.asInstanceOf[AnyRef])) getStmtPred(t, env)
        else if (childAST(thenBranch).eq(nested_ast_elem.asInstanceOf[AnyRef]))
          getCondExprPred(condition, env)
        else if (elseBranch.isDefined && childAST(elseBranch.get).eq(nested_ast_elem.asInstanceOf[AnyRef])) {
          if (elifs.isEmpty) getCondExprPred(condition, env)
          else getCompoundPred(elifs, env)
        } else {
          getStmtPred(nested_ast_elem.asInstanceOf[AST], env)
        }
      }
      // pred of thenBranch is the condition itself
      // and if we are in condition, we strike for a previous elifstatement or the if itself using
      // getPredSameLevel
      case t@ElifStatement(condition, thenBranch) => {
        if (condition.eq(nested_ast_elem.asInstanceOf[AnyRef])) predElifStatement(t, env)
        else List(condition)
      }

      case t: CaseStatement => List(t)
      case t: DefaultStatement => List(t)

      case s: Statement => getStmtPred(s, env)

      case _ => List()
    }
  }

  private def predElifStatement(a: ElifStatement, env: ASTEnv): List[AST] = {
    val surrounding_if = parentAST(a, env)
    surrounding_if match {
      case IfStatement(condition, thenBranch, elifs, elseBranch) => {
        var res: List[AST] = List()
        val prev_elifs = elifs.reverse.dropWhile(_.entry.eq(a.asInstanceOf[AnyRef]).unary_!).drop(1)
        val elif_feature_expr = env.featureExpr(a)
        val ifdef_blocks = determineIfdefBlocks(prev_elifs, env)
        val grouped_ifdef_blocks = groupIfdefBlocks(ifdef_blocks, env)
        val typed_grouped_ifdef_blocks = determineTypeOfGroupedIfdefBlocks(grouped_ifdef_blocks, env)
        res = res ++ determineFollowingElements(elif_feature_expr, typed_grouped_ifdef_blocks, env).merge

        // if no previous elif statement is found, the result is condition
        if (!res.isEmpty) {
          var newres: List[AST] = List()
          for (elem_res <- res) {
            elem_res match {
              case ElifStatement(elif_condition, _) =>
                newres = getCondExprPred(elif_condition, env) ++ newres
              case _ => newres = elem_res :: newres
            }
          }
          newres
        }
        else getCondExprPred(condition, env)
      }
      case _ => List()
    }
  }

  // method to catch surrounding while, for, ... statement, which is the follow item of a last element in it's list
  private def followUpSucc(nested_ast_elem: AnyRef, env: ASTEnv): Option[List[AST]] = {
    nested_ast_elem match {
      case _: ReturnStatement => {
        findPriorASTElem[FunctionDef](nested_ast_elem, env) match {
          case None => assert(false, "return statement should always occur within a function statement"); Some(List())
          case Some(f) => Some(List(f))
        }
      }
      case _ => {
        val surrounding_parent = parentAST(nested_ast_elem, env)
        surrounding_parent match {
          // depending on in which part of the loop we are, do the next
          // expr3 -> expr2
          // expr2 -> s or t
          // s -> expr3 or expr2 or s
          case t@ForStatement(_, expr2, Some(expr3), s) if (nested_ast_elem.eq(expr3.asInstanceOf[AnyRef])) =>
            if (expr2.isDefined) Some(getCondExprSucc(expr2.get, env))
            else Some(getCondStmtSucc(t, s, env))
          case t@ForStatement(_, Some(expr2), _, s) if (nested_ast_elem.eq(expr2.asInstanceOf[AnyRef])) =>
            Some(getCondStmtSucc(t, s, env) ++ getStmtSucc(t, env))
          case t@ForStatement(_, expr2, expr3, s) => {
            if (expr3.isDefined) Some(getCondExprSucc(expr3.get, env))
            else if (expr2.isDefined) Some(getCondExprSucc(expr2.get, env))
            else Some(getCondStmtSucc(t, childAST(s), env))
          }
          case WhileStatement(expr, s) => Some(List(expr))
          case DoStatement(expr, s) => Some(List(expr))

          // after control flow comes out of a branch from an IfStatement,
          // we go for the next element in the row
          case t: IfStatement => Some(getStmtSucc(t, env))
          case t: ElifStatement => followUpSucc(t, env)

          case t: Expr => followUpSucc(t, env)
          case t: Statement => Some(getStmtSucc(t, env))

          case t: FunctionDef => Some(List(t))
          case _ => None
        }
      }
    }
  }

  // method to find prior element to a break statement
  private def findPriorASTElem2BreakStatement(a: AnyRef, env: ASTEnv): Option[AST] = {
    val aparent = env.parent(a)
    aparent match {
      case t: ForStatement => Some(t)
      case t: WhileStatement => Some(t)
      case t: DoStatement => Some(t)
      case t: SwitchStatement => Some(t)
      case null => None
      case t: AnyRef => findPriorASTElem2BreakStatement(t, env)
    }
  }

  // method to find prior element to a continue statement
  private def findPriorASTElem2ContinueStatement(a: AnyRef, env: ASTEnv): Option[AST] = {
    val aparent = env.parent(a)
    aparent match {
      case t: ForStatement => Some(t)
      case t: WhileStatement => Some(t)
      case t: DoStatement => Some(t)
      case null => None
      case t: AnyRef => findPriorASTElem2ContinueStatement(t, env)
    }
  }

  // method to catch surrounding ast element, which precedes the given nested_ast_element
  private def followUpPred(nested_ast_elem: AnyRef, env: ASTEnv): Option[List[AST]] = {

    def handleSwitch(t: AST) = {
      val prior_switch = findPriorASTElem[SwitchStatement](t, env)
      assert(prior_switch.isDefined, "default statement without surrounding switch")
      prior_switch.get match {
        case SwitchStatement(expr, _) => {
          val lconds = getCondExprPred(expr, env)
          if (env.previous(t) != null) Some(lconds ++ getStmtPred(t, env))
          else {
            val tparent = parentAST(t, env)
            if (tparent.isInstanceOf[CaseStatement]) Some(tparent :: lconds)  // TODO rewrite, nested cases.
            else Some(lconds ++ getStmtPred(tparent, env))
          }
        }
      }
    }

    nested_ast_elem match {

      // case or default statements belong only to switch statements
      case t: CaseStatement => handleSwitch(t)
      case t: DefaultStatement => handleSwitch(t)

      case _ => {
        val surrounding_parent = parentAST(nested_ast_elem, env)
        surrounding_parent match {
          // coming up a for statement
          case t@ForStatement(_, _, Some(expr3), s) if (nested_ast_elem.eq(expr3.asInstanceOf[AnyRef])) =>
            Some(getCondStmtPred(t, childAST(s), env))
          case t@ForStatement(_, Some(expr2), expr3, s) if (nested_ast_elem.eq(expr2.asInstanceOf[AnyRef])) =>
            if (expr3.isDefined) Some(getCondExprPred(expr3.get, env))
            else Some(getCondStmtPred(t, childAST(s), env))
          case t@ForStatement(expr1, expr2, expr3, s) if (nested_ast_elem.eq(childAST(s).asInstanceOf[AnyRef])) =>
            if (expr2.isDefined) Some(getCondExprPred(expr2.get, env))
            else if (expr3.isDefined) Some(getCondExprPred(expr3.get, env))
            else {
              var res: List[AST] = List()
              if (expr1.isDefined) res = res ++ getCondExprPred(expr1.get, env)
              else res = getStmtPred(t, env) ++ res
              res = res ++ getCondStmtPred(t, s, env)
              Some(res)
            }
          case t@WhileStatement(expr, _) => {
            if (nested_ast_elem.eq(expr)) Some(getStmtPred(t, env))
            else Some(getCondExprPred(expr, env))
          }
          case t@DoStatement(expr, s) => {
            if (nested_ast_elem.eq(expr)) Some(getCondStmtPred(t, s, env))
            else Some(getCondExprPred(expr, env) ++ getStmtPred(t, env))
          }

          // control flow comes either out of:
          // elseBranch: elifs + condition is the result
          // elifs: rest of elifs + condition
          // thenBranch: condition
          case t@IfStatement(condition, thenBranch, elifs, elseBranch) => {
            if (nested_ast_elem.eq(condition)) Some(getStmtPred(t, env))
            else if (nested_ast_elem.eq(childAST(thenBranch))) Some(List(condition))
            else if (elseBranch.isDefined && nested_ast_elem.eq(childAST(elseBranch.get))) {
              if (!elifs.isEmpty) Some(getCompoundPred(elifs, env))
              else Some(getCondExprPred(condition, env))
            } else {
              Some(predElifStatement(nested_ast_elem.asInstanceOf[ElifStatement], env))
            }
          }
          case t@ElifStatement(condition, _) => Some(getCondExprPred(condition, env))
          case t@SwitchStatement(expr, _) => Some(getCondExprPred(expr, env))
          case t: CaseStatement => Some(List(t))
          case t: DefaultStatement => handleSwitch(t)

          case t: CompoundStatementExpr => followUpPred(t, env)
          case t: Statement => Some(getStmtPred(t, env))
          case t: FunctionDef => Some(List(t))
          case _ => None
        }
      }
    }
  }

  // we have to check possible successor nodes in at max three steps:
  // 1. get direct successors with same annotation; if yes stop; if not go to step 2.
  // 2. get all annotated elements at the same level and check whether we find a definite set of successor nodes
  //    if yes stop; if not go to step 3.
  // 3. get the parent of our node and determine successor nodes of it
  private def getStmtSucc(s: AST, env: ASTEnv): List[AST] = {
    val next_ifdef_blocks = getNextIfdefBlocks(s, env)
    val next_equal_annotated_ast_element = getNextEqualAnnotatedASTElem(s, next_ifdef_blocks)
    next_equal_annotated_ast_element match {
      // 1.
      case Some(x) => List(x)
      case None => {
        val feature_expr_s_statement = env.featureExpr(s)
        val successor_list = determineFollowingElements(feature_expr_s_statement, next_ifdef_blocks.drop(1), env)
        successor_list match {
          case Left(s_list) => s_list // 2.
          case Right(s_list) => s_list ++ followUpSucc(s, env).getOrElse(List()) // 3.
        }
      }
    }
  }

  // this method filters BreakStatements
  // a break belongs to next outer loop (for, while, do-while)
  // or a switch statement (see [2])
  // use this method with the loop or switch body!
  // so we recursively go over the structure of the ast elems
  // in case we find a break, we add it to the result list
  // in case we hit another loop or switch we return the empty list
  private def filterBreakStatements(c: Conditional[Statement], env: ASTEnv): List[BreakStatement] = {
    def filterBreakStatementsHelper(a: Any): List[BreakStatement] = {
      a match {
        case t: BreakStatement => List(t)
        case _: SwitchStatement => List()
        case _: ForStatement => List()
        case _: WhileStatement => List()
        case _: DoStatement => List()
        case l: List[_] => l.flatMap(filterBreakStatementsHelper(_))
        case x: Product => x.productIterator.toList.flatMap(filterBreakStatementsHelper(_))
        case _ => List()
      }
    }
    filterBreakStatementsHelper(c)
  }

  // this method filters ContinueStatements
  // according to [2]: A continue statement shall appear only in or as a
  // loop body
  // use this method only with the loop body!
  private def filterContinueStatements(c: Conditional[Statement], env: ASTEnv): List[ContinueStatement] = {
    def filterContinueStatementsHelper(a: Any): List[ContinueStatement] = {
      a match {
        case t: ContinueStatement => List(t)
        case _: ForStatement => List()
        case _: WhileStatement => List()
        case _: DoStatement => List()
        case l: List[_] => l.flatMap(filterContinueStatementsHelper(_))
        case x: Product => x.productIterator.toList.flatMap(filterContinueStatementsHelper(_))
        case _ => List()
      }
    }
    filterContinueStatementsHelper(c)
  }

  // this method filters all CaseStatements
  private def filterCaseStatements(c: Conditional[Statement], env: ASTEnv): List[CaseStatement] = {
    def filterCaseStatementsHelper(a: Any): List[CaseStatement] = {
      a match {
        case t@CaseStatement(_, s) => List(t) ++ (if (s.isDefined) filterCaseStatementsHelper(s.get) else List())
        case SwitchStatement => List()
        case l: List[_] => l.flatMap(filterCaseStatementsHelper(_))
        case x: Product => x.productIterator.toList.flatMap(filterCaseStatementsHelper(_))
        case _ => List()
      }
    }
    filterCaseStatementsHelper(c)
  }

  // although the standard says that a case statement only has one default statement
  // we may have differently annotated default statements
  private def filterDefaultStatements(c: Conditional[Statement], env: ASTEnv): List[DefaultStatement] = {
    def filterDefaultStatementsHelper(a: Any): List[DefaultStatement] = {
      a match {
        case SwitchStatement => List()
        case t: DefaultStatement => List(t)
        case l: List[_] => l.flatMap(filterDefaultStatementsHelper(_))
        case x: Product => x.productIterator.toList.flatMap(filterDefaultStatementsHelper(_))
        case _ => List()
      }
    }
    filterDefaultStatementsHelper(c)
  }

  // in predecessor determination we have to dig in into elements at certain points
  // we dig into ast that have an Conditional part, such as for, while, ...
  private def rollUp(a: AST, env: ASTEnv): List[AST] = {
    a match {

      // in general all elements from the different branches (thenBranch, elifs, elseBranch)
      // can be predecessors
      case t@IfStatement(condition, thenBranch, elifs, elseBranch) => {
        var res = List[AST]()
        if (elseBranch.isDefined) res = res ++ getCondStmtPred(t, childAST(elseBranch.get), env)
        if (!elifs.isEmpty) {
          for (Opt(f, elif@ElifStatement(_, thenBranch)) <- elifs) {
            res = res ++ getCondStmtPred(elif, childAST(thenBranch), env).flatMap(rollUp(_, env))
          }

          // without an else branch, the condition of elifs are possible predecessors of a
          if (elseBranch.isEmpty) res = res ++ getCompoundPred(elifs, env)
        }
        res = res ++ getCondStmtPred(t, childAST(thenBranch), env).flatMap(rollUp(_, env))

        if (elifs.isEmpty && elseBranch.isEmpty)
          res = res ++ List(condition)
        res
      }
      case ElifStatement(condition, thenBranch) => {
        var res = List[AST]()
        res = condition :: res
        res = res ++ getCondStmtPred(a, childAST(thenBranch), env).flatMap(rollUp(_, env))
        res
      }
      case t@SwitchStatement(expr, s) => {
        val lbreaks = filterBreakStatements(s, env)
        val ldefaults = filterDefaultStatements(s, env)

        // TODO both lbreaks and ldefaults are empty!
        if (ldefaults.isEmpty) lbreaks ++ getCondExprPred(expr, env)
        else lbreaks ++ ldefaults.flatMap(rollUpJumpStatement(_, true, env))
      }

      case t@WhileStatement(expr, s) => List(expr) ++ filterBreakStatements(s, env)
      case t@DoStatement(expr, s) => List(expr) ++ filterBreakStatements(s, env)
      case t@ForStatement(_, Some(expr2), _, s) => List(expr2) ++ filterBreakStatements(s, env)
      case t@ForStatement(_, _, _, s) => filterBreakStatements(s, env)

      case CompoundStatement(innerStatements) => getCompoundPred(innerStatements, env).flatMap(rollUp(_, env))

      case _ => List(a)
    }
  }

  // we have a separate rollUp function for CaseStatement, DefaultStatement, and BreakStatement
  // because using rollUp in pred determination (see above) will return wrong results
  private def rollUpJumpStatement(a: AST, fromSwitch: Boolean, env: ASTEnv): List[AST] = {
    a match {
      case t@CaseStatement(_, s) if (s.isDefined) =>
        getCondStmtPred(t, childAST(s), env).flatMap(rollUpJumpStatement(_, false, env))

      // the code that belongs to the jump target default is either reachable via nextAST from the
      // default statement: this first case statement here
      // or the code is nested in the DefaultStatement, so we match it with the next case statement
      case t@DefaultStatement(_) if (nextAST(t, env) != null && fromSwitch) => {
        val dparent = findPriorASTElem[CompoundStatement](t, env)
        assert(dparent.isDefined, "default statement always occurs in a compound statement of a switch")
        dparent.get match {
          case CompoundStatement(innerStatements) => getCompoundPred(innerStatements, env)
        }
      }
      case t@DefaultStatement(s) if (s.isDefined) =>
        getCondStmtPred(t, childAST(s), env).flatMap(rollUpJumpStatement(_, false, env))
      case _: BreakStatement => List()
      case _ => List(a)
    }
  }

  // we have to check possible predecessor nodes in at max three steps:
  // 1. get direct predecessor with same annotation; if yes stop; if not go to step 2.
  // 2. get all annotated elements at the same level and check whether we find a definite set of predecessor nodes
  //    if yes stop; if not go to step 3.
  // 3. get the parent of our node and determine predecessor nodes of it
  private def getStmtPred(s: AST, env: ASTEnv): List[AST] = {
    val previous_ifdef_blocks = getPreviousIfdefBlocks(s, env)
    val previous_equal_annotated_ast_elem = getNextEqualAnnotatedASTElem(s, previous_ifdef_blocks)
    previous_equal_annotated_ast_elem match {
      // 1.
      case Some(BreakStatement()) => List()
      case Some(x) => List(x).flatMap(rollUpJumpStatement(_, false, env))
      case None => {
        val feature_expr_s_statement = env.featureExpr(s)
        val predecessor_list = determineFollowingElements(feature_expr_s_statement, previous_ifdef_blocks.drop(1), env)
        predecessor_list match {
          case Left(p_list) => p_list.flatMap(rollUpJumpStatement(_, false, env)) // 2.
          case Right(p_list) => p_list.flatMap(rollUpJumpStatement(_, false, env)) ++ followUpPred(s, env).getOrElse(List()) // 3.
        }
      }
    }
  }

  // given a list of AST elements, determine successor AST elements based on feature expressions
  private def getCompoundSucc(l: List[AST], env: ASTEnv): List[AST] = {
    if (l.isEmpty) List()
    else {
      val ifdef_blocks = determineIfdefBlocks(l, env)
      val grouped_ifdef_blocks = groupIfdefBlocks(ifdef_blocks, env).reverse
      val typed_grouped_ifdef_blocks = determineTypeOfGroupedIfdefBlocks(grouped_ifdef_blocks, env).reverse
      val list_parent_feature_expr = env.featureExpr(env.parent(l.head))
      val successor_list = determineFollowingElements(list_parent_feature_expr, typed_grouped_ifdef_blocks, env)

      successor_list match {
        case Left(s_list) => s_list
        case Right(s_list) => s_list ++ followUpSucc(l.head, env).getOrElse(List())
      }
    }
  }

  // given a list of AST elements, determine predecessor AST elements based on feature expressions
  private def getCompoundPred(l: List[AST], env: ASTEnv): List[AST] = {
    if (l.isEmpty) List()
    else {
      val ifdef_blocks = determineIfdefBlocks(l.reverse, env)
      val grouped_ifdef_blocks = groupIfdefBlocks(ifdef_blocks, env)
      val typed_grouped_ifdef_blocks = determineTypeOfGroupedIfdefBlocks(grouped_ifdef_blocks, env)
      val list_parent_feature_expr = env.featureExpr(env.parent(l.reverse.head))
      val predecessor_list = determineFollowingElements(list_parent_feature_expr, typed_grouped_ifdef_blocks, env)

      predecessor_list match {
        case Left(p_list) => p_list
        case Right(p_list) => (p_list ++ followUpPred(l.reverse.head, env).getOrElse(List()))
      }
    }
  }

  // returns a list next AST elems grouped according to feature expressions
  private def getNextIfdefBlocks(s: AST, env: ASTEnv): List[(Int, IfdefBlocks)] = {
    val prev_and_next_ast_elems = prevASTElems(s, env) ++ nextASTElems(s, env).drop(1)
    val ifdef_blocks = determineIfdefBlocks(prev_and_next_ast_elems, env)
    val grouped_ifdef_blocks = groupIfdefBlocks(ifdef_blocks, env)
    val typed_grouped_ifdef_blocks = determineTypeOfGroupedIfdefBlocks(grouped_ifdef_blocks, env)
    getTailList(s, typed_grouped_ifdef_blocks)
  }

  private def getPreviousIfdefBlocks(s: AST, env: ASTEnv) = {
    val prev_and_next_ast_elems = prevASTElems(s, env) ++ nextASTElems(s, env).drop(1)
    val prev_and_next_ast_elems_reversed = prev_and_next_ast_elems.reverse
    val ifdef_blocks = determineIfdefBlocks(prev_and_next_ast_elems_reversed, env)
    val grouped_ifdef_blocks = groupIfdefBlocks(ifdef_blocks, env)
    val typed_grouped_ifdef_blocks = determineTypeOfGroupedIfdefBlocks(grouped_ifdef_blocks, env)
    getTailList(s, typed_grouped_ifdef_blocks)
  }

  // o occurs somewhere in l
  // determine where using getElementAfterO and return the result
  // if o does not have a next element return None
  // otherwise return Some of the next element
  private def getNextEqualAnnotatedASTElem(o: AST, l: List[(Int, IfdefBlocks)]): Option[AST] = {
    if (l.isEmpty) return None
    val l_with_eq_annotated_elems_as_o = l.head._2

    // using eq to determine o in IfdefBlocks
    // if found get the next element in the list and return it
    def getElementAfterO(ls: IfdefBlocks): Option[AST] = {
      var found_o = false
      for (ifdef_block <- ls) {
        found_o = false
        for (stmt <- ifdef_block) {
          if (found_o) return Some(stmt)
          if (stmt.eq(o)) found_o = true
        }
      }
      None
    }

    getElementAfterO(l_with_eq_annotated_elems_as_o)
  }

  // get list with o and all following lists
  private def getTailList(s: AST, l: List[(Int, IfdefBlocks)]): List[(Int, IfdefBlocks)] = {
    // iterate each sublist of the incoming tuples (Int, List[List[Opt[_]]] combine equality check
    // and drop elements in which s occurs

    def contains(ls: (Int, IfdefBlocks)): Boolean = {
      for (ifdef_block <- ls._2) {
        for (stmt <- ifdef_block) {
          if (stmt.eq(s)) return true
        }
      }
      false
    }

    l.dropWhile(contains(_).unary_!)
  }

  // code works both for succ and pred determination
  // based on the type of the IfdefBlocks (True(0), Optional (1), Alternative (2))
  // the function computes the following elements
  private def determineFollowingElements(f: FeatureExpr,
                                         l: List[(Int, IfdefBlocks)],
                                         env: ASTEnv): Either[List[AST], List[AST]] = {
    var res = List[AST]()
    for (e <- l) {
      e match {
        case (0, ifdef_blocks) => return Left(res ++ List(ifdef_blocks.head.head))
        case (1, ifdef_blocks) => {
          res = res ++ ifdef_blocks.flatMap({
            x => List(x.head)
          })
          val e_feature_expr = env.featureExpr(ifdef_blocks.head.head)
          if (e_feature_expr.equivalentTo(f) && e._1 == 1) return Left(res)
        }
        case (2, ifdef_blocks) => return Left(res ++ ifdef_blocks.flatMap({
          x => List(x.head)
        }))
      }
    }
    Right(res)
  }

  // determine recursively all succs check
  def getAllSucc(i: AST, env: ASTEnv) = {
    var r = List[(AST, List[AST])]()
    var s = List(i)
    var d = List[AST]()
    var c: AST = null

    while (!s.isEmpty) {
      c = s.head
      s = s.drop(1)

      if (d.filter(_.eq(c)).isEmpty) {
        r = (c, succ(c, env)) :: r
        s = s ++ r.head._2
        d = d ++ List(c)
      }
    }
    r
  }

  // determine recursively all pred
  def getAllPred(i: AST, env: ASTEnv) = {
    var r = List[(AST, List[AST])]()
    var s = List(i)
    var d = List[AST]()
    var c: AST = null

    while (!s.isEmpty) {
      c = s.head
      s = s.drop(1)

      if (d.filter(_.eq(c)).isEmpty) {
        r = (c, pred(c, env)) :: r
        s = s ++ r.head._2
        d = d ++ List(c)
      }
    }
    r
  }

  // the following functions belong to detection and type cateorization of ifdef blocks
  // pack similar elements into sublists
  private def pack[T](f: (T, T) => Boolean)(l: List[T]): List[List[T]] = {
    if (l.isEmpty) List()
    else (l.head :: l.tail.takeWhile(f(l.head, _))) :: pack(f)(l.tail.dropWhile(f(l.head, _)))
  }

  // group consecutive Opts in a list and return a list of list containing consecutive (feature equivalent) opts
  // e.g.:
  // List(Opt(true, Id1), Opt(fa, Id2), Opt(fa, Id3)) => List(List(Opt(true, Id1)), List(Opt(fa, Id2), Opt(fa, Id3)))
  private[crewrite] def determineIfdefBlocks(l: List[AST], env: ASTEnv): IfdefBlocks = {
    pack[AST](env.featureExpr(_) equivalentTo env.featureExpr(_))(l)
  }

  // group List[List[AST]] according their feature expressions
  // later one should imply the not of previous ones; therefore using l.reverse
  private def groupIfdefBlocks(l: IfdefBlocks, env: ASTEnv) = {
    def checkImplication(a: AST, b: AST) = {
      val as = env.get(a)._1.toSet
      val bs = env.get(b)._1.toSet
      val cs = as.intersect(bs)
      as.--(cs).foldLeft(FeatureExpr.base)(_ and _).implies(bs.--(cs).foldLeft(FeatureExpr.base)(_ and _).not()).isTautology()
    }
    pack[List[AST]]({
      (x, y) => checkImplication(x.head, y.head)
    })(l.reverse).reverse
  }

  // get type of IfdefBlocks:
  // 0 -> only true values
  // 1 -> #if-(#elif)* block
  // 2 -> #if-(#elif)*-#else block
  private def determineTypeOfGroupedIfdefBlocks(l: List[IfdefBlocks], env: ASTEnv): List[(Int, IfdefBlocks)] = {

    l match {
      case (h :: t) => {
        val feature_expr_over_ifdef_blocks = h.map({
          e => env.featureExpr(e.head)
        })

        if (feature_expr_over_ifdef_blocks.foldLeft(FeatureExpr.base)(_ and _).isTautology())
          (0, h) :: determineTypeOfGroupedIfdefBlocks(t, env)
        else if (feature_expr_over_ifdef_blocks.map(_.not()).foldLeft(FeatureExpr.base)(_ and _).isContradiction())
          (2, h.reverse) :: determineTypeOfGroupedIfdefBlocks(t, env)
        else (1, h) :: determineTypeOfGroupedIfdefBlocks(t, env)
      }
      case Nil => List()
    }
  }
}

// defines and uses we can jump to using succ
// beware of List[Opt[_]]!! all list elements can possibly have a different annotation
trait Variables extends ASTNavigation {
  val uses: PartialFunction[Any, Set[Id]] = {
    case a: Any => findUses(a)
  }

  private def findUses(e: Any): Set[Id] = {
    e match {
      case ForStatement(expr1, expr2, expr3, _) => uses(expr1) ++ uses(expr2) ++ uses(expr3)
      case ReturnStatement(Some(x)) => uses(x)
      case WhileStatement(expr, _) => uses(expr)
      case DeclarationStatement(d) => uses(d)
      case Declaration(_, init) => init.flatMap(uses).toSet
      case InitDeclaratorI(_, _, Some(i)) => uses(i)
      case AtomicNamedDeclarator(_, id, _) => Set(id)
      case NestedNamedDeclarator(_, nestedDecl, _) => uses(nestedDecl)
      case Initializer(_, expr) => uses(expr)
      case i@Id(name) => Set(i)
      case PointerPostfixSuffix(kind, id) => Set(id)
      case FunctionCall(params) => params.exprs.map(_.entry).flatMap(uses).toSet
      case ArrayAccess(expr) => uses(expr)
      case PostfixExpr(p, s) => uses(p) ++ uses(s)
      case UnaryExpr(_, ex) => uses(ex)
      case SizeOfExprU(expr) => uses(expr)
      case CastExpr(_, expr) => uses(expr)
      case PointerDerefExpr(castExpr) => uses(castExpr)
      case PointerCreationExpr(castExpr) => uses(castExpr)
      case UnaryOpExpr(kind, castExpr) => uses(castExpr)
      case NAryExpr(ex, others) => uses(ex) ++ others.flatMap(uses).toSet
      case NArySubExpr(_, ex) => uses(ex)
      case ConditionalExpr(condition, _, _) => uses(condition)
      case ExprStatement(expr) => uses(expr)
      case AssignExpr(target, _, source) => uses(target) ++ uses(source)
      case o@Opt(_, entry) => uses(o.entry)
      case _ => Set()
    }
  }

  val defines: PartialFunction[Any, Set[Id]] = {
    case DeclarationStatement(d) => defines(d)
    case Declaration(_, init) => init.flatMap(defines).toSet
    case InitDeclaratorI(a, _, _) => defines(a)
    case AtomicNamedDeclarator(_, i, _) => Set(i)
    case o@Opt(_, entry) => defines(entry)
    case _ => Set()
  }
}

trait Liveness extends AttributionBase with Variables with ConditionalControlFlow {

  private def updateMap(m: Map[FeatureExpr, Set[Id]],
                        e: (FeatureExpr, Set[Id]),
                        op: (Set[Id], Set[Id]) => Set[Id]): Map[FeatureExpr, Set[Id]] = {
    val key = m.find(_._1.equivalentTo(e._1))
    key match {
      case None => m.+(e)
      case Some((k, v)) => m.+((k, op(e._2, v)))
    }
  }

  // cache for in; we have to store all tuples of (a, env) their because using
  // (a, env) always creates a new one!!! and circular internally uses another
  // IdentityHashMap and uses (a, env) as a key there.
  private val astIdenEnvHM = new IdentityHashMap[AST, (AST, ASTEnv)]()

  // cf. http://www.cs.colostate.edu/~mstrout/CS553/slides/lecture03.pdf
  // page 5
  //  in(n) = uses(n) + (out(n) - defines(n))
  // out(n) = for s in succ(n) r = r + in(s); r
  val in: PartialFunction[(Any, ASTEnv), Map[FeatureExpr, Set[Id]]] = {
    circular[(Any, ASTEnv), Map[FeatureExpr, Set[Id]]](Map()) {
      case t@(e, env) => {
        val u = uses(e)
        val d = defines(e)
        var res = out(t)
        if (!d.isEmpty) {
          val dhfexp = env.featureExpr(d.head)
          res = updateMap(res, (dhfexp, d), {
            _ -- _
          })
        }
        if (!u.isEmpty) {
          val uhfexp = env.featureExpr(u.head)
          res = updateMap(res, (uhfexp, u), {
            _ ++ _
          })
        }
        res
      }
    }
  }

  val out: PartialFunction[(Any, ASTEnv), Map[FeatureExpr, Set[Id]]] =
    circular[(Any, ASTEnv), Map[FeatureExpr, Set[Id]]](Map()) {
      case t@(e, env) => {
        val sl = succ(e, env)
        var res = Map[FeatureExpr, Set[Id]]()
        for (a <- sl) {
          if (!astIdenEnvHM.containsKey(a))
            astIdenEnvHM.put(a, (a, env))
          for (el <- in(astIdenEnvHM.get(a)))
            res = updateMap(res, el, {
              _ ++ _
            })
        }
        res
      }
    }
}
