package de.fosd.typechef.featureexpr

import LazyLib._
import collection.mutable.Map
import collection.mutable.WeakHashMap
import collection.mutable.HashMap
import collection.mutable.ArrayBuffer
import scala.ref.WeakReference
import java.io.Writer

/**
 * External interface for construction of non-boolean feature expressions
 * (mostly delegated to FExprBuilder)
 *
 * Also provides access to the primitives base and dead (for true and false)
 * and allows to create DefinedExternal nodes
 */
object FeatureExpr extends FeatureExprValueOps {

    def createComplement(expr: FeatureExprValue): FeatureExprValue = FExprBuilder.applyUnaryOperation(expr)(~_)
    def createNeg(expr: FeatureExprValue) = FExprBuilder.applyUnaryOperation(expr)(-_)
    def createBitAnd(left: FeatureExprValue, right: FeatureExprValue) = FExprBuilder.applyBinaryOperation(left, right)(_ & _)
    def createBitOr(left: FeatureExprValue, right: FeatureExprValue) = FExprBuilder.applyBinaryOperation(left, right)(_ | _)
    def createDivision(left: FeatureExprValue, right: FeatureExprValue): FeatureExprValue = FExprBuilder.applyBinaryOperation(left, right)(
        (l, r) => if (r == 0) ErrorValue[Long]("division by zero") else FExprBuilder.createValue(l / r))
    def createModulo(left: FeatureExprValue, right: FeatureExprValue) = FExprBuilder.applyBinaryOperation(left, right)(_ % _)
    def createEquals(left: FeatureExprValue, right: FeatureExprValue) = FExprBuilder.evalRelation(left, right)(_ == _)
    def createNotEquals(left: FeatureExprValue, right: FeatureExprValue) = FExprBuilder.evalRelation(left, right)(_ != _)
    def createLessThan(left: FeatureExprValue, right: FeatureExprValue) = FExprBuilder.evalRelation(left, right)(_ < _)
    def createLessThanEquals(left: FeatureExprValue, right: FeatureExprValue) = FExprBuilder.evalRelation(left, right)(_ <= _)
    def createGreaterThan(left: FeatureExprValue, right: FeatureExprValue) = FExprBuilder.evalRelation(left, right)(_ > _)
    def createGreaterThanEquals(left: FeatureExprValue, right: FeatureExprValue) = FExprBuilder.evalRelation(left, right)(_ >= _)
    def createMinus(left: FeatureExprValue, right: FeatureExprValue) = FExprBuilder.applyBinaryOperation(left, right)(_ - _)
    def createMult(left: FeatureExprValue, right: FeatureExprValue) = FExprBuilder.applyBinaryOperation(left, right)(_ * _)
    def createPlus(left: FeatureExprValue, right: FeatureExprValue) = FExprBuilder.applyBinaryOperation(left, right)(_ + _)
    def createPwr(left: FeatureExprValue, right: FeatureExprValue) = FExprBuilder.applyBinaryOperation(left, right)(_ ^ _)
    def createShiftLeft(left: FeatureExprValue, right: FeatureExprValue) = FExprBuilder.applyBinaryOperation(left, right)(_ << _)
    def createShiftRight(left: FeatureExprValue, right: FeatureExprValue) = FExprBuilder.applyBinaryOperation(left, right)(_ >> _)

    def createInteger(value: Long): FeatureExprValue = FExprBuilder.createValue(value)
    def createCharacter(value: Char): FeatureExprValue = FExprBuilder.createValue(value)
    def createValue[T](v: T): FeatureExprTree[T] = FExprBuilder.createValue(v)


    def createDefinedExternal(name: String): DefinedExternal = FExprBuilder.definedExternal(name)
    def createDefinedMacro(name: String, macroTable: FeatureProvider): FeatureExpr = FExprBuilder.definedMacro(name, macroTable)


    //helper
    def createIf(condition: FeatureExpr, thenBranch: FeatureExpr, elseBranch: FeatureExpr): FeatureExpr = FExprBuilder.createIf(condition, thenBranch, elseBranch)
    def createIf[T](condition: FeatureExpr, thenBranch: FeatureExprTree[T], elseBranch: FeatureExprTree[T]): FeatureExprTree[T] = FExprBuilder.createIf(condition, thenBranch, elseBranch)

    def createImplies(left: FeatureExpr, right: FeatureExpr) = left implies right
    def createEquiv(left: FeatureExpr, right: FeatureExpr) = left equiv right

    val base: FeatureExpr = True
    val dead: FeatureExpr = False

    private[featureexpr] case class StructuralEqualityWrapper(f: FeatureExpr) {
        final override def equals(that: Any) =
            super.equals(that) || (that match {
                case StructuralEqualityWrapper(thatF) => f.equal1Level(thatF)
                case _ => false
            })
        final override def hashCode = f.hashCode
        final def unwrap = f
    }

}

object FeatureExprHelper {
    def resolveDefined(macro: DefinedMacro, macroTable: FeatureProvider): FeatureExpr =
        macroTable.getMacroCondition(macro.feature)

    private var freshFeatureNameCounter = 0
    def calcFreshFeatureName(): String = {
        freshFeatureNameCounter = freshFeatureNameCounter + 1;
        "__fresh" + freshFeatureNameCounter;
    }
}

/**
 * Propositional (or boolean) feature expressions.
 *
 * Feature expressions are compared on object identity (comparing them for equivalence is
 * an additional but expensive operation). Connectives such as "and", "or" and "not"
 * memoize results, so that the operation yields identical results on identical parameters.
 * Classes And, Or and Not are made package-private, and their constructors wrapped
 * through companion objects, to prevent the construction of formulas in any other way.
 *
 * However, this is not yet enough to guarantee the 'maximal sharing' property, because the
 * and/or operators are also associative, but the memoization cannot be associative.
 * Papers on hash-consing explain that one needs to perform a further normalization step.
 *
 * More in general, one can almost prove a theorem called the weak-canonicalization guarantee:
 *
 * If at a given time during program execution, two formula objects represent structurally
 * equal formulas, i.e. which are deeply equal modulo the order of operands of "and" and "or",
 * then they are represented by the same object.
 *
 * XXX: HOWEVER, that the associative property does not hold with pointer equality:
 * (a and b) and c ne a and (b and c). Hopefully this is fixable through different caching.
 *
 * Note that this is not related to formula equivalence, rather to pointer equality.
 * This does not hold for formulas existing at different moments, because caches
 * are implemented using weak references, so if a formula disappears from the heap
 * it is recreated. However, this is not observable for the code.
 *
 * The weak canonicalization property, if true, should allows also ensuring the strong-canonicalization guarantee:
 * If at a given time during program execution, two formula objects a and b
 * represent equivalent formulas, then a.toCNF eq b.toCNF (where eq denotes pointer equality).
 *
 * CNF canonicalization, by construction, ensures that a.toCNF and b.toCNF are structurally equal.
 * The weak canonicalization property would also ensure that they are the same object.
 *
 * It would be interesting to see what happens for toEquiCNF.
 */
sealed abstract class FeatureExpr {
    def or(that: FeatureExpr): FeatureExpr = FExprBuilder.or(this, that)
    def and(that: FeatureExpr): FeatureExpr = FExprBuilder.and(this, that)
    def not(): FeatureExpr = FExprBuilder.not(this)

    def unary_! = not
    def &(that: FeatureExpr) = and(that)
    def |(that: FeatureExpr) = or(that)

    def orNot(that: FeatureExpr) = this or (that.not)
    def andNot(that: FeatureExpr) = this and (that.not)
    def implies(that: FeatureExpr) = this.not.or(that)
    def mex(that: FeatureExpr): FeatureExpr = (this and that).not
    def xor(that: FeatureExpr) = (this or that) and (this mex that)

    // According to advanced textbooks, this representation is not always efficient:
    // not (a equiv b) generates 4 clauses, of which 2 are tautologies.
    // In positions of negative polarity (i.e. contravariant?), a equiv b is best transformed to
    // (a and b) or (!a and !b). However, currently it seems that we never construct not (a equiv b).
    // Be careful if that changes, though.
    def equiv(that: FeatureExpr) = (this implies that) and (that implies this)

    def isContradiction(): Boolean = isContradiction(NoFeatureModel)
    def isTautology(): Boolean = isTautology(NoFeatureModel)
    def isDead(): Boolean = isContradiction(NoFeatureModel)
    def isBase(): Boolean = isTautology(NoFeatureModel)
    def isSatisfiable(): Boolean = isSatisfiable(NoFeatureModel)
    /**
     * FM -> X is tautology if FM.implies(X).isTautology or
     * !FM.and.(x.not).isSatisfiable
     *
     **/
    def isTautology(fm: FeatureModel): Boolean = !this.not.isSatisfiable(fm)
    def isContradiction(fm: FeatureModel): Boolean = !isSatisfiable(fm)
    /**
     * x.isSatisfiable(fm) is short for x.and(fm).isSatisfiable
     * but is faster because FM is cached
     */
    def isSatisfiable(fm: FeatureModel): Boolean = cacheIsSatisfiable.getOrElseUpdate(fm, new SatSolver().isSatisfiable(toCnfEquiSat, fm))

    /**
     * Check structural equality, assuming that all component nodes have already been canonicalized.
     * The default implementation checks for pointer equality.
     */
    private[featureexpr] def equal1Level(that: FeatureExpr) = this eq that

    final override def equals(that: Any) = super.equals(that)

    protected def calcHashCode = super.hashCode

    /**
     * uses a SAT solver to determine whether two expressions are
     * equivalent.
     *
     * for performance reasons, it checks pointer
     * equivalence first, but won't use the recursive equals on aexpr
     * (there should only be few cases when equals is more
     * accurate than eq, which are not worth the performance
     * overhead)
     */
    def equivalentTo(that: FeatureExpr): Boolean = (this eq that) || (this equiv that).isTautology();
    def equivalentTo(that: FeatureExpr, fm: FeatureModel): Boolean = (this eq that) || (this equiv that).isTautology(fm);

    protected def indent(level: Int): String = "\t" * level

    final lazy val size: Int = calcSize
    protected def calcSize: Int

    /**
     * heuristic to determine whether a feature expression is small
     * (may be used to decide whether to inline it or not)
     *
     * use with care
     */
    def isSmall(): Boolean = size <= 10

    /**
     * replaces all DefinedMacro tokens by their full expansion.
     *
     * the resulting feature expression contains only DefinedExternal nodes as leafs
     * and can be printed and read again
     */
    lazy val resolveToExternal: FeatureExpr = FExprBuilder.resolveToExternal(this)

    /**
     * checks whether there is some unresolved macro (DefinedMacro) somewhere
     * in the expression tree
     */
    lazy val isResolved: Boolean = calcIsResolved
    private def calcIsResolved: Boolean = {
        //exception used to stop at the first found Macro
        //map used for caching (to not look twice at the same subtree)
        class FoundUnresolvedException extends Exception
        try {
            this.mapDefinedExpr({
                case e: DefinedMacro => {throw new FoundUnresolvedException(); e}
                case e => e
            }, Map())
            return true
        } catch {
            case e: FoundUnresolvedException => return false
        }
    }

    /**
     * map function that applies to all leafs in the feature expression (i.e. all DefinedExpr nodes)
     */
    def mapDefinedExpr(f: DefinedExpr => FeatureExpr, cache: Map[FeatureExpr, FeatureExpr]): FeatureExpr

    /**
     * Converts this formula to a textual expression.
     */
    def toTextExpr: String

    /**
     * Prints the textual representation of this formula on a Writer. The result shall be equivalent to
     * p.print(toTextExpr), but it should avoid consuming so much temporary space.
     * @param p the output Writer
     */
    def print(p: Writer) = p.write(toTextExpr)
    def debug_print(indent: Int): String

    private var cache_cnf: FeatureExpr = null
    private var cache_cnfEquiSat: FeatureExpr = null

    /**
     * creates an equivalent feature expression in CNF
     *
     * be aware of exponential explosion. consider using toCnfEquiSat instead if possible
     */
    def toCNF(): FeatureExpr = {
        if (cache_cnf == null) {cache_cnf = calcCNF; cache_cnfEquiSat = cache_cnf}
        assert(CNFHelper.isCNF(cache_cnf))
        //XXX: add and test!
        //cache_cnfEquiSat.cache_cnf = cache_cnf
        //cache_cnfEquiSat.cache_cnfEquiSat = cache_cnf
        cache_cnf
    }
    /**
     * creates an equisatisfiable feature expression in CNF
     *
     * the result is not equivalent but will yield the same result
     * in satisifiability tests with SAT solvers
     *
     * the algorithm introduces new variables and is faster than toCNF
     */
    def toCnfEquiSat(): FeatureExpr = {
        if (cache_cnfEquiSat == null) cache_cnfEquiSat = calcCNFEquiSat
        assert(CNFHelper.isCNF(cache_cnfEquiSat))
        //XXX: add and test!
        //cache_cnfEquiSat.cache_cnfEquiSat = cache_cnfEquiSat
        cache_cnfEquiSat
    }
    protected def calcCNF: FeatureExpr
    protected def calcCNFEquiSat: FeatureExpr

    private val cacheIsSatisfiable: WeakHashMap[FeatureModel, Boolean] = WeakHashMap()
    //only access these caches from FExprBuilder
    private[featureexpr] val andCache: WeakHashMap[FeatureExpr, WeakReference[FeatureExpr]] = new WeakHashMap()
    private[featureexpr] val orCache: WeakHashMap[FeatureExpr, WeakReference[FeatureExpr]] = new WeakHashMap()
    private[featureexpr] var notCache: Option[NotReference[FeatureExpr]] = None

    /**
     * Retrieves the memoized representation of this.not, if any exists; it is useful only to look for duplicated
     * elements in a clause. Note that two non-trivial formula might be
     * opposite, still using retrieveMemoizedNot for the test might
     * fail; e.g., a and b and !a or !b might be built independently.
     * XXX: Tillmann suggested transforming this into a boolean predicate,
     * rather than using null as a meaningful value.
     */
    private[featureexpr] def retrieveMemoizedNot = notCache match {
        case Some(NotRef(x)) => x
        case _ => null
    }

    /**
     * simple translation into a FeatureExprValue if needed for some reason
     * (creates IF(expr, 1, 0))
     */
    def toFeatureExprValue: FeatureExprValue =
        FExprBuilder.createIf(this, FExprBuilder.createValue(1), FExprBuilder.createValue(0))

    // This field keeps the wrapper referenced in a reference cycle, so that the lifecycle of this object and the wrapper match.
    // This is crucial to use the wrapper in a WeakHashMap!
    private[featureexpr] val wrap = FeatureExpr.StructuralEqualityWrapper(this)

    /**
     * helper function for statistics and such that determines which
     * features are involved in this feature expression
     */
    def collectDistinctFeatures: Set[DefinedExternal] = {
        var result: Set[DefinedExternal] = Set()
        this.mapDefinedExpr(_ match {
            case e: DefinedExternal => result += e; e
            case e => e
        }, Map())
        result
    }
    /**
     * counts the number of features in this expression for statistic
     * purposes
     */
    def countDistinctFeatures: Int = collectDistinctFeatures.size
}

class FeatureException(msg: String) extends RuntimeException(msg)

class FeatureArithmeticException(msg: String) extends FeatureException(msg)

// XXX: this should be recognized by the caller and lead to clean termination instead of a stack trace. At least,
// however, this is only a concern for erroneous input anyway (but isn't it our point to detect it?)
case class ErrorFeature(msg: String) extends FeatureExpr {
    private def error: Nothing = throw new FeatureArithmeticException(msg)
    override def calcCNF = error
    override def calcCNFEquiSat = error
    override def toTextExpr = error
    override def calcSize = error
    override def mapDefinedExpr(f: DefinedExpr => FeatureExpr, cache: Map[FeatureExpr, FeatureExpr]) = error
    override def debug_print(x: Int) = error
}

// Cache the computed hashCode. Note that this can make sense only if you override calcHashCode,
// and the computation is complex enough. Currently this means only not, and even then I'm not sure it's the best.
abstract class HashCachingFeatureExpr extends FeatureExpr {
    protected val cachedHash = calcHashCode

    final override def hashCode = cachedHash
}


/**
 * Central builder class, responsible for simplification of expressions during creation
 * and for extensive caching.
 */
private[featureexpr] object FExprBuilder {
    private val ifCache: HashMap[(FeatureExpr, FeatureExprTree[_], FeatureExprTree[_]), WeakReference[FeatureExprTree[_]]] = new HashMap()
    private val featureCache: Map[String, WeakReference[DefinedExternal]] = Map()
    private var macroCache: Map[String, WeakReference[DefinedMacro]] = Map()
    private val valCache: Map[Any, WeakReference[Value[_]]] = Map()
    private val resolvedCache: WeakHashMap[FeatureExpr, FeatureExpr] = WeakHashMap()
    private val hashConsingCache: WeakHashMap[FeatureExpr.StructuralEqualityWrapper, WeakReference[FeatureExpr.StructuralEqualityWrapper]] = WeakHashMap()

    private def cacheGetOrElseUpdate[A, B <: AnyRef](map: Map[A, WeakReference[B]], key: A, op: => B): B = {
        def update() = {val d = op; map(key) = new WeakReference[B](d); d}
        map.get(key) match {
            case Some(WeakRef(value)) => value
            case _ => update()
        }
    }

    private def getFromCache(cache: WeakHashMap[FeatureExpr, WeakReference[FeatureExpr]], key: FeatureExpr): Option[FeatureExpr] = {
        cache.get(key) match {
            case Some(WeakRef(f)) => Some(f)
            case _ => None
        }
    }
    private def binOpCacheGetOrElseUpdate(a: FeatureExpr,
                                          b: FeatureExpr,
                                          getCache: FeatureExpr => WeakHashMap[FeatureExpr, WeakReference[FeatureExpr]],
                                          featThunk: => FeatureExpr): FeatureExpr = {
        var result = getFromCache(getCache(a), b)
        if (result == None)
            result = getFromCache(getCache(b), a)
        if (result == None) {
            val feat = featThunk
            result = Some(feat)
            val weakRef = new WeakReference(feat)
            getCache(a).update(b, weakRef)
            //XXX it is enough to update one object, because we are searching in both of them anyway
            //            getCache(b).update(a, weakRef)
        }
        result.get
    }

    private def canonical(f: FeatureExpr) = cacheGetOrElseUpdate(hashConsingCache, f.wrap, f.wrap).unwrap

    /*
     * It seems that with the four patterns to optimize and/or, it's more difficult to
     * produce a formula with duplicated literals - you need two levels of nesting for that to happen.
     * Duplications is removed in variations of:
     * a && (b || !a), where a and b can be any formula, and && and || can be replaced by any connective.
     * Examples where duplication appears:
     * a && !(a || b) - fixable by implementing DeMorgan laws in Not.
     * (a || b) && (b || c)
     * a && (b || (c && a)) - note the two levels of nesting. The inner a could
     * be removed in this particular case using shortcircuiting, but there does
     * not seem to be an easy way of implementing this.
     *
     *
     * Please note that due to duality, the code below is essentially
     * duplicated (andOr() is dual to orAnd(), and() to or(), and so on).
     * Remember that to dualize this code, one must swap or with and, and False with True.
     * Please check that it does not go out of sync.
     */
    // XXX: in various places, we merge sets with an O(N) reduction. It allows looking up the memoized results, but now
    // that we later canonicalize everything, it is not clear whether we should still do it. Such occurrences are marked
    // below with "XXX: O(N) set rebuild". Note however that to make them O(1) one needs to write a O(1) hash calculator
    // for them as well - very simple, just some work to do. See specialized constructors in AndOrUnExtractor.

    // Optimized representation of e and (o: Or)
    private def andOr(e: FeatureExpr, o: Or) =
        if (o.clauses contains e) //o = (e || o'), then e || o == e && (e || o') == e
            e
        else if (o.clauses contains (e.not))
            fastAnd(e, createOr(o.clauses - e.not)) //XXX: O(N) set rebuild
        else
            And(Set(e, o))

    private def andOrOr(o1: Or, o2: Or) =
        if (o1.clauses contains o2) //o = (e || o'), then e || o == e && (e || o') == e
            o2
        else if (o2.clauses contains o1) //o = (e || o'), then e || o == e && (e || o') == e
            o1
        else if (o1.clauses contains (o2.not))
            fastAnd(o2, createOr(o1.clauses - o2.not)) //XXX: O(N) set rebuild
        else if (o2.clauses contains (o1.not))
            fastAnd(o1, createOr(o2.clauses - o1.not)) //XXX: O(N) set rebuild
        else if ((o1.clauses.size == 2) && (o2.clauses.size == 2)) {
            //simple but common pattern ((a or b) and (a or !b)) == a
            val i1 = o1.clauses.iterator
            val o11 = i1.next
            val o12 = i1.next
            val i2 = o2.clauses.iterator
            val o21 = i2.next
            val o22 = i2.next
            if (o11 == o21 && o12 == o22.not) o11
            else if (o12 == o21 && o11 == o22.not) o12
            else if (o11 == o22 && o12 == o21.not) o11
            else if (o12 == o22 && o11 == o21.not) o12
            else And(Set(o1, o2))
        }
        else
            And(Set(o1, o2))

    //Optimized representation of e and (a: And)
    private def andAnd(e: FeatureExpr, a: And) =
        if (a.clauses contains e)
            a
        else if (a.clauses contains (e.not))
            False
        else
            And(a.clauses + e, a, e)

    //Do not canonicalize the function at each step, do it only in the public wrappers.
    private def fastAnd(a: FeatureExpr, b: FeatureExpr): FeatureExpr =
        (a, b) match {
            case (e1, e2) if (e1 == e2) => e1
            case (_, False) => False
            case (False, _) => False
            case (True, e) => e
            case (e, True) => e
            case (e1, e2) if ((e1.retrieveMemoizedNot == e2) || (e1 == e2.retrieveMemoizedNot)) => False
            case other =>
                binOpCacheGetOrElseUpdate(a, b, _.andCache,
                    other match {
                        case (a1: And, a2: And) =>
                            a2.clauses.foldLeft[FeatureExpr](a1)(fastAnd(_, _)) //XXX: O(N) set rebuild
                        case (a: And, e) => andAnd(e, a)
                        case (e, a: And) => andAnd(e, a)
                        case (o: Or, e: Or) => andOrOr(e, o)
                        case (o: Or, e) => andOr(e, o)
                        case (e, o: Or) => andOr(e, o)
                        case (e1, e2) => And(Set(e1, e2))
                    })
        }

    def createAnd(clauses: Traversable[FeatureExpr]) =
        canonical(clauses.foldLeft[FeatureExpr](True)(fastAnd(_, _)))

    //Optimized representation of e or (a: And)
    private def orAnd(e: FeatureExpr, a: And) =
        if (a.clauses contains e) //a == (e && a'), then e || a == e || (e && a') == e
            e
        else if (a.clauses contains (e.not))
            fastOr(e, createAnd(a.clauses - e.not)) //XXX: O(N) set rebuild
        else
            Or(Set(e, a))

    private def orAndAnd(a1: And, a2: And) =
        if (a2.clauses contains a1) //a == (e && a'), then e || a == e || (e && a') == e
            a1
        else if (a1.clauses contains a2) //a == (e && a'), then e || a == e || (e && a') == e
            a2
        else if (a2.clauses contains (a1.not))
            fastOr(a1, createAnd(a2.clauses - a1.not)) //XXX: O(N) set rebuild
        else if (a1.clauses contains (a2.not))
            fastOr(a2, createAnd(a1.clauses - a2.not)) //XXX: O(N) set rebuild
        else if ((a1.clauses.size == 2) && (a2.clauses.size == 2)) {
            //simple but common pattern ((a and b) or (a and !b)) == a
            val i1 = a1.clauses.iterator
            val a11 = i1.next
            val a12 = i1.next
            val i2 = a2.clauses.iterator
            val a21 = i2.next
            val a22 = i2.next
            if (a11 == a21 && a12 == a22.not) a11
            else if (a12 == a21 && a11 == a22.not) a12
            else if (a11 == a22 && a12 == a21.not) a11
            else if (a12 == a22 && a11 == a21.not) a12
            else Or(Set(a1, a2))
        }
        else
            Or(Set(a1, a2))

    private def orOr(e: FeatureExpr, o: Or) =
        if (o.clauses contains e)
            o
        else if (o.clauses contains (e.not))
            True
        else
            Or(o.clauses + e, o, e)

    def fastOr(a: FeatureExpr, b: FeatureExpr): FeatureExpr =
    //simple cases without caching
        (a, b) match {
            case (e1, e2) if (e1 == e2) => e1
            case (_, True) => True
            case (True, _) => True
            case (False, e) => e
            case (e, False) => e
            case (e1, e2) if ((e1.retrieveMemoizedNot == e2) || (e1 == e2.retrieveMemoizedNot)) => True
            case other =>
                binOpCacheGetOrElseUpdate(a, b, _.orCache,
                    other match {
                        case (o1: Or, o2: Or) =>
                            o2.clauses.foldLeft[FeatureExpr](o1)(fastOr(_, _)) //XXX: O(N) set rebuild
                        case (o: Or, e) => orOr(e, o)
                        case (e, o: Or) => orOr(e, o)
                        case (e: And, a: And) => orAndAnd(e, a)
                        case (e, a: And) => orAnd(e, a)
                        case (a: And, e) => orAnd(e, a)
                        case (e1, e2) => Or(Set(e1, e2))
                    })
        }

    def createOr(clauses: Traversable[FeatureExpr]) =
        canonical(clauses.foldLeft[FeatureExpr](False)(fastOr(_, _)))

    //End of dualized code.


    def and(a: FeatureExpr, b: FeatureExpr): FeatureExpr = canonical(fastAnd(a, b))
    def or(a: FeatureExpr, b: FeatureExpr): FeatureExpr = canonical(fastOr(a, b))

    def not(a: FeatureExpr): FeatureExpr =
        a match {
            case True => False
            case False => True
            case n: Not => n.expr
            case e => {
                e.notCache match {
                    case Some(NotRef(res)) => res
                    case _ =>
                        def storeCache(e: FeatureExpr, neg: FeatureExpr) = {e.notCache = Some(new NotReference(neg)); e}
                        val res = canonical(e match {
                            /* This transformation is expensive, so we need to store
                            * a reference to e in the created expression. However,
                            * this enables more occasions for simplification and
                            * ensures that the result is in Negation Normal Form.
                            */
                            case And(clauses) => createOr(clauses.map(_.not))
                            case Or(clauses) => createAnd(clauses.map(_.not))
                            case _ => new Not(e) //Triggered by leaves.
                        })
                        storeCache(res, e)
                        //Store in the old expression a reference to the new one.
                        storeCache(e, res)
                        res
                }
            }
        }

    def definedExternal(name: String) = cacheGetOrElseUpdate(featureCache, name, new DefinedExternal(name))

    //create a macro definition (which expands to the current entry in the macro table; the current entry is stored in a closure-like way).
    //a form of caching provided by MacroTable, which we need to repeat here to create the same FeatureExpr object
    def definedMacro(name: String, macroTable: FeatureProvider): FeatureExpr = {
        val macroCondition = macroTable.getMacroCondition(name)
        if (macroCondition.isSmall) {
            macroCondition
        } else {
            val (conditionName, conditionDef) = macroTable.getMacroConditionCNF(name)

            /**
             * definedMacros are equal if they have the same Name and the same expansion! (otherwise they refer to
             * the macro at different points in time and should not be considered equal)
             * actually, we only check the expansion name which is unique for each DefinedMacro anyway
             */
            cacheGetOrElseUpdate(macroCache, conditionName,
                new DefinedMacro(
                    name,
                    macroTable.getMacroCondition(name),
                    conditionName,
                    conditionDef))
        }
    }


    private def propagateError[T](left: FeatureExprTree[T], right: FeatureExprTree[T]): Option[ErrorValue[T]] = {
        (left, right) match {
            case (ErrorValue(msg1), ErrorValue(msg2)) => Some(ErrorValue(msg1 + ";" + msg2))
            case (ErrorValue(msg), _) => Some(ErrorValue(msg))
            case (_, ErrorValue(msg)) => Some(ErrorValue(msg))
            case _ => None
        }
    }

    def evalRelation[T](smaller: FeatureExprTree[T], larger: FeatureExprTree[T])(relation: (T, T) => Boolean): FeatureExpr = {
        propagateError(smaller, larger) match {
            case Some(ErrorValue(msg)) => return ErrorFeature(msg)
            case _ =>
                (smaller, larger) match {
                    case (Value(a), Value(b)) => if (relation(a, b)) True else False
                    case (If(e1, a1, b1), If(e2, a2, b2)) => createIf(e1,
                        createIf(e2, evalRelation(a1, a2)(relation), evalRelation(a1, b2)(relation)),
                        createIf(e2, evalRelation(b1, a2)(relation), evalRelation(b1, b2)(relation)))
                    case (If(e, a, b), x) => createIf(e, evalRelation(a, x)(relation), evalRelation(b, x)(relation))
                    case (x, If(e, a, b)) => createIf(e, evalRelation(x, a)(relation), evalRelation(x, b)(relation))
                    case _ => throw new Exception("evalRelation: unexpected " + (smaller, larger))
                }
        }
    }

    def applyBinaryOperation[T, U <% FeatureExprTree[T]](left: FeatureExprTree[T], right: FeatureExprTree[T])(operation: (T, T) => U): FeatureExprTree[T] = {
        propagateError(left, right) match {
            case Some(err) => return err
            case _ =>
                (left, right) match {
                    case (Value(a), Value(b)) => operation(a, b)
                    case (If(e1, a1, b1), If(e2, a2, b2)) => createIf(e1,
                        createIf(e2, applyBinaryOperation(a1, a2)(operation), applyBinaryOperation(a1, b2)(operation)),
                        createIf(e2, applyBinaryOperation(b1, a2)(operation), applyBinaryOperation(b1, b2)(operation)))
                    case (If(e, a, b), x) => createIf(e, applyBinaryOperation(a, x)(operation), applyBinaryOperation(b, x)(operation))
                    case (x, If(e, a, b)) => createIf(e, applyBinaryOperation(x, a)(operation), applyBinaryOperation(x, b)(operation))
                    case _ => throw new Exception("applyBinaryOperation: unexpected " + (left, right))
                }
        }
    }

    def applyUnaryOperation[T](expr: FeatureExprTree[T])(operation: T => T): FeatureExprTree[T] = expr match {
        case Value(a) => createValue(operation(a))
        case If(e, a, b) => createIf(e, applyUnaryOperation(a)(operation), applyUnaryOperation(b)(operation))
        case _ => throw new Exception("applyUnaryOperation: unexpected " + expr)
    }

    def createIf[T](expr: FeatureExpr, thenBr: FeatureExprTree[T], elseBr: FeatureExprTree[T]): FeatureExprTree[T] = expr match {
        case True => thenBr
        case False => elseBr
        case _ => {
            if (thenBr == elseBr) thenBr
            else cacheGetOrElseUpdate(ifCache, (expr, thenBr, elseBr), new If(expr, thenBr, elseBr)).asInstanceOf[FeatureExprTree[T]]
        }
    }

    def createIf(expr: FeatureExpr, thenBr: FeatureExpr, elseBr: FeatureExpr): FeatureExpr = (expr and thenBr) or (expr.not and elseBr)

    def createValue[T](v: T): FeatureExprTree[T] = cacheGetOrElseUpdate(valCache, v, new Value[T](v)).asInstanceOf[FeatureExprTree[T]]

    def resolveToExternal(expr: FeatureExpr): FeatureExpr = expr.mapDefinedExpr({
        case e: DefinedMacro => e.presenceCondition.resolveToExternal
        case e => e
    }, resolvedCache)
}


////////////////////////////
// propositional formulas //
////////////////////////////
/**
 * True and False. They are represented as special cases of And and Or
 * with no clauses, as it is common practice, for instance in the resolution algorithm.
 *
 * True is the zero element for And, while False is the zero element for Or.
 * Therefore, True can be represented as an empty conjunction, while False
 * by an empty disjunction.
 *
 * One can imagine to build a disjunction incrementally, by having an empty one
 * be considered as false, so that each added operand can make the resulting
 * formula true. Dually, an empty conjunction is true, and and'ing clauses can
 * make it false.
 *
 * This avoids introducing two new leaf nodes to handle (avoiding some bugs causing NoLiteralException).
 *
 * Moreover, since those are valid formulas, they would otherwise be valid but
 * non-canonical representations, and avoiding the very existence of such things
 * simplifies ensuring that our canonicalization algorithms actually work.
 *
 * The use of only canonical representations for True and False is ensured thanks to the
 * apply methods of the And and Or companion objects, which convert any empty set of
 * clauses into the canonical True or False object.
 */
object True extends And(Set()) with DefaultPrint {
    override def toString = "True"
    override def toTextExpr = "1"
    override def debug_print(ind: Int) = indent(ind) + toTextExpr + "\n"
    override def isSatisfiable(fm: FeatureModel) = true
}

object False extends Or(Set()) with DefaultPrint {
    override def toString = "False"
    override def toTextExpr = "0"
    override def debug_print(ind: Int) = indent(ind) + toTextExpr + "\n"
    override def isSatisfiable(fm: FeatureModel) = false
}

trait DefaultPrint extends FeatureExpr {override def print(p: Writer) = p.write(toTextExpr)}

//The class name means And/Or (Un)Extractor.
abstract class AndOrUnExtractor[This <: BinaryLogicConnective[This]] {
    def identity: FeatureExpr
    def unapply(x: This) = Some(x.clauses)
    private def optBuild(clauses: Set[FeatureExpr], defaultRes: => This) = {
        clauses.size match {
            case 0 => identity
            /* The case below seems to not occur, but better include it
             * for extra robustness, to ensure the weak canonicalization property. */
            case 1 => clauses.head
            case _ => defaultRes
        }
    }

    private[featureexpr] def apply(clauses: Set[FeatureExpr]) = optBuild(clauses, createRaw(clauses))
    private[featureexpr] def apply(clauses: Set[FeatureExpr], old: This, newF: FeatureExpr) = optBuild(clauses, createRaw(clauses, old, newF))

    //Factory methods for the actual object type
    protected def createRaw(clauses: Set[FeatureExpr]): This
    protected def createRaw(clauses: Set[FeatureExpr], old: This, newF: FeatureExpr): This
}

//objects And and Or are just boilerplate instances of AndOrUnExtractor
object And extends AndOrUnExtractor[And] {
    def identity = True
    protected def createRaw(clauses: Set[FeatureExpr]) = new And(clauses)
    protected def createRaw(clauses: Set[FeatureExpr], old: And, newF: FeatureExpr) = new And(clauses, old, newF)
}

object Or extends AndOrUnExtractor[Or] {
    def identity = False
    protected def createRaw(clauses: Set[FeatureExpr]) = new Or(clauses)
    protected def createRaw(clauses: Set[FeatureExpr], old: Or, newF: FeatureExpr) = new Or(clauses, old, newF)
}

private[featureexpr]
abstract class BinaryLogicConnective[This <: BinaryLogicConnective[This]] extends FeatureExpr {
    private[featureexpr] def clauses: Set[FeatureExpr]

    def operName: String
    def create(clauses: Traversable[FeatureExpr]): FeatureExpr
    //Can't declare This as return type - the optimizations in FExprBuilder are such that it might build an object of
    //unexpected type.

    override def equal1Level(that: FeatureExpr) = that match {
        case e: BinaryLogicConnective[_] =>
            e.primeHashMult == primeHashMult && //check this as a class tag
                    e.clauses.subsetOf(clauses) &&
                    e.clauses.size == clauses.size
        case _ => false
    }

    def primeHashMult: Int
    override def calcHashCode = primeHashMult * clauses.map(_.hashCode).foldLeft(0)(_ + _)

    // We need to compute the hashCode lazily (and pay a penalty when accessing it) because too many temporaries are
    // created. We might want to change that, though (see comments above mentioning "XXX: O(N) set rebuild"), and
    // retest this choice.
    // In a few cases, however, we compute the hashcode eagerly and incrementally (to reuse old hashcode computations).
    protected var cachedHash: Option[Int] = None
    final override def hashCode =
        cachedHash match {
            case Some(hash) =>
                hash
            case None =>
                val hash = calcHashCode
                cachedHash = Some(hash)
                hash
        }

    //Constructors of subclasses must call either of these methods. Since the hash computation is reasonably cheap
    protected def presetHash(old: This, newF: FeatureExpr) =
    //This computation is O(1); throwing out the hashCode and recomputing it would be O(n), and when growing
    //a Set, one element at a time, the time complexity of hash updates would be potentially O(n^2).
        cachedHash = Some(old.hashCode + primeHashMult * newF.hashCode)


    override def toString = clauses.mkString("(", operName, ")")
    override def toTextExpr = clauses.map(_.toTextExpr).mkString("(", " " + operName + operName + " ", ")")
    override def print(p: Writer) = {
        trait PrintValue
        case object NoPrint extends PrintValue
        case object Printed extends PrintValue
        case class ToPrint[T](x: T) extends PrintValue
        p write "("
        clauses.map(x => ToPrint(x)).foldLeft[PrintValue](NoPrint)({
            case (NoPrint, ToPrint(c)) => {c.print(p); Printed}
            case (Printed, ToPrint(c)) => {p.write(" " + operName + operName + " "); c.print(p); Printed}
        })
        p write ")"
    }
    override def debug_print(ind: Int) = indent(ind) + operName + "\n" + clauses.map(_.debug_print(ind + 1)).mkString

    override def calcSize = clauses.foldLeft(0)(_ + _.size)
    override def mapDefinedExpr(f: DefinedExpr => FeatureExpr, cache: Map[FeatureExpr, FeatureExpr]): FeatureExpr = cache.getOrElseUpdate(this, {
        var anyChange = false
        val newClauses = clauses.map(x => {
            val y = x.mapDefinedExpr(f, cache)
            anyChange |= x != y
            y
        })
        if (anyChange)
            create(newClauses)
        else
            this
    })
}

//private[featureexpr]
class And(val clauses: Set[FeatureExpr]) extends BinaryLogicConnective[And] {
    //Use this constructor when adding newF to old, because it reuses the old hash.
    def this(clauses: Set[FeatureExpr], old: And, newF: FeatureExpr) = {
        this (clauses)
        presetHash(old, newF)
    }

    override def primeHashMult = 37
    override def operName = "&"
    override def create(clauses: Traversable[FeatureExpr]) = FExprBuilder.createAnd(clauses)

    override protected def calcCNF: FeatureExpr = FExprBuilder.createAnd(clauses.map(_.toCNF))
    override protected def calcCNFEquiSat: FeatureExpr = FExprBuilder.createAnd(clauses.map(_.toCnfEquiSat))
}

//private[featureexpr]
class Or(val clauses: Set[FeatureExpr]) extends BinaryLogicConnective[Or] {
    //Use this constructor when adding newF to old, because it reuses the old hash.
    def this(clauses: Set[FeatureExpr], old: Or, newF: FeatureExpr) = {
        this (clauses)
        presetHash(old, newF)
    }

    override def primeHashMult = 97
    override def operName = "|"
    override def create(clauses: Traversable[FeatureExpr]) = FExprBuilder.createOr(clauses)

    override protected def calcCNF: FeatureExpr =
        combineCNF(clauses.map(_.toCNF))
    override protected def calcCNFEquiSat: FeatureExpr = {
        val cnfchildren = clauses.map(_.toCnfEquiSat)
        //XXX: There is no need to estimate the size this way, we could maybe
        //use the more precise size method. However, possibly this is the
        //correct calculation of the number of generated clauses. The name
        //predictedCNFClauses is maybe misleading, but I introduced it, and it
        //would be my fault then. PG
        //
        //heuristic: up to a medium size do not introduce new variables but use normal toCNF mechanism
        //rationale: we might actually simplify the formula by transforming it into CNF and in such cases it's not very expensive
        def size(child: FeatureExpr) = child match {case And(inner) => inner.size; case _ => 1}
        val predictedCNFClauses = cnfchildren.foldRight(1)((x, y) => if (y <= 16) size(x) * y else y)
        if (predictedCNFClauses <= 16)
            combineCNF(cnfchildren)
        else
            combineEquiCNF(cnfchildren)
    }


    /**
     * multiplies all clauses
     *
     * for n CNF expressions with e1, e2, .., en clauses
     * this mechanism produces e1*e2*...*en clauses
     */
    private def combineCNF(cnfchildren: Set[FeatureExpr]) =
        FExprBuilder.createAnd(
            if (cnfchildren.exists(_.isInstanceOf[And])) {
                var conjuncts = Set[FeatureExpr](False)
                for (child <- cnfchildren) {
                    child match {
                        case And(innerChildren) =>
                            //conjuncts@(a_1 & a_2) | innerChildren@(b_1 & b_2)
                            //becomes conjuncts'@(a_1 | b_1) & (a_1 | b_2) & (a_2 | b_1) & (a_2 | b_2).
                            conjuncts = conjuncts.flatMap(
                                conjunct => innerChildren.map(
                                    _ or conjunct))
                        case _ =>
                            conjuncts = conjuncts.map(_ or child)
                    }
                }
                assert(conjuncts.forall(c => CNFHelper.isClause(c) || c == True || c == False))
                conjuncts
            } else {
                /* The context adds an extra And, because a canonical CNF is an conjunction of disjunctions.
                 * Currently this extra And is optimized away, but here I do not want to rely on this detail. */
                List(FExprBuilder.createOr(cnfchildren))
            })

    /**
     * Produce a CNF formula equiSatisfiable to the disjunction of @param cnfchildren.
     * Introduces new variables to avoid exponential behavior
     *
     * for n CNF expressions with e1, e2, .., en clauses
     * this mechanism produces n new variables and results
     * in e1+e2+..+en+1 clauses
     *
     * Algorithm: we need to represent Or(X_i, i=i..n), where X_i are subformulas in CNF. Each of them is a conjunction
     * (or a literal, as a degenerate case).
     * We produce a clause Or(Z_i) and clauses such that Z_i implies X_i, conjuncted together. For literals we just
     * reuse the literal; if X_i = And(Y_ij, j=1..e_i), we produce clauses (Z_i implies Y_ij) for j=1..e_i.
     */
    private def combineEquiCNF(cnfchildren: Set[FeatureExpr]) =
        if (cnfchildren.exists(_.isInstanceOf[And])) {
            var orClauses = ArrayBuffer[FeatureExpr]() //list of Or expressions
            var renamedDisjunction = ArrayBuffer[FeatureExpr]()
            for (child <- cnfchildren) {
                child match {
                    case And(innerChildren) =>
                        val freshFeature = FExprBuilder.definedExternal(FeatureExprHelper.calcFreshFeatureName())
                        orClauses ++= innerChildren.map(freshFeature implies _)
                        renamedDisjunction += freshFeature
                    case e =>
                        renamedDisjunction += e
                }
            }
            orClauses += FExprBuilder.createOr(renamedDisjunction)
            FExprBuilder.createAnd(orClauses)
            /*val (orClauses, renamedDisjunction) = cnfchildren.map({
                case And(innerChildren) =>
                    val freshFeature = FExprBuilder.definedExternal(FeatureExprHelper.calcFreshFeatureName())
                    (innerChildren.map(freshFeature implies _), freshFeature)
                case e =>
                    (Set.empty, e)
            }).unzip
            FExprBuilder.createAnd(orClauses.flatten[FeatureExpr] + FExprBuilder.createOr(renamedDisjunction))*/
        } else FExprBuilder.createOr(cnfchildren)

}

//private[featureexpr]
class Not(val expr: FeatureExpr) extends HashCachingFeatureExpr {
    override def calcHashCode = 701 * expr.hashCode
    override def equal1Level(that: FeatureExpr) = that match {
        case Not(expr2) => expr eq expr2
        case _ => false
    }

    override def toString = "!" + expr.toString
    override def toTextExpr = "!" + expr.toTextExpr
    override def print(p: Writer) = {
        p.write("!")
        expr.print(p)
    }
    override def debug_print(ind: Int) = indent(ind) + "!\n" + expr.debug_print(ind + 1)

    override def calcSize = expr.size
    override def mapDefinedExpr(f: DefinedExpr => FeatureExpr, cache: Map[FeatureExpr, FeatureExpr]): FeatureExpr = cache.getOrElseUpdate(this, {
        val newExpr = expr.mapDefinedExpr(f, cache)
        if (newExpr != expr) FExprBuilder.not(newExpr) else this
    })
    override protected def calcCNF: FeatureExpr = expr match {
        case And(children) => FExprBuilder.createOr(children.map(_.not.toCNF)).toCNF
        case Or(children) => FExprBuilder.createAnd(children.map(_.not.toCNF))
        case e => this
    }

    override protected def calcCNFEquiSat: FeatureExpr = expr match {
        case And(children) => FExprBuilder.createOr(children.map(_.not.toCnfEquiSat())).toCnfEquiSat()
        case Or(children) => FExprBuilder.createAnd(children.map(_.not.toCnfEquiSat()))
        case e => this
    }

    private[featureexpr] override def retrieveMemoizedNot = expr
}

object Not {
    def unapply(x: Not) = Some(x.expr)
}

/**
 * Leaf nodes of propositional feature expressions, either an external
 * feature defined by the user or another feature expression from a macro.
 */
abstract class DefinedExpr extends FeatureExpr {
    /*
     * This method is overriden by children case classes to return the name.
     * It would be nice to have an actual field here, but that doesn't play nicely with case classes;
     * avoiding case classes and open-coding everything would take too much code.
     */
    def feature: String
    def debug_print(level: Int): String = indent(level) + feature + "\n";
    def accept(f: FeatureExpr => Unit): Unit = f(this)
    def satName = feature
    //used for sat solver only to distinguish extern and macro
    def isExternal: Boolean
    override def calcSize = 1
    override def mapDefinedExpr(f: DefinedExpr => FeatureExpr, cache: Map[FeatureExpr, FeatureExpr]): FeatureExpr = cache.getOrElseUpdate(this, f(this))
    override def calcCNF = this
    override def calcCNFEquiSat = this
}

object DefinedExpr {
    def unapply(f: DefinedExpr): Option[DefinedExpr] = f match {
        case x: DefinedExternal => Some(x)
        case x: DefinedMacro => Some(x)
        case _ => None
    }
    def checkFeatureName(name: String) = assert(name != "1" && name != "0" && name != "")
}

/**
 * external definition of a feature (cannot be decided to Base or Dead inside this file)
 */
class DefinedExternal(name: String) extends DefinedExpr {
    DefinedExpr.checkFeatureName(name)

    def feature = name
    override def toTextExpr = "definedEx(" + name + ")";
    override def toString = "def(" + name + ")"
    def countSize() = 1
    def isExternal = true
}

/**
 * definition based on a macro, still to be resolved using the macro table
 * (the macro table may not contain DefinedMacro expressions, but only DefinedExternal)
 * assumption: expandedName is unique and may be used for comparison
 */
class DefinedMacro(val name: String, val presenceCondition: FeatureExpr, val expandedName: String, val presenceConditionCNF: Susp[FeatureExpr /*CNF*/ ]) extends DefinedExpr {
    DefinedExpr.checkFeatureName(name)

    def feature = name
    override def toTextExpr = "defined(" + name + ")"
    override def toString = "macro(" + name + ")"
    override def satName = expandedName
    def countSize() = 1
    def isExternal = false
}

object DefinedMacro {
    def unapply(x: DefinedMacro) = Some((x.name, x.presenceCondition, x.expandedName, x.presenceConditionCNF))
}
