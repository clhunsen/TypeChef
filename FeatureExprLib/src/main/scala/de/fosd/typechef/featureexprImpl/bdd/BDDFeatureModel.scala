package de.fosd.typechef.featureexprImpl.bdd

import org.sat4j.core.{VecInt, Vec}
import org.sat4j.specs.{IVec, IVecInt}
import BDDFeatureExprLibrary._
import de.fosd.typechef.featureexprUtil.SATBasedFeatureModel

/**
 * the feature model is a special container for a single feature expression
 * that is used very often in a conjunction
 *
 * it stores the formula in an array structure easy to process by the
 * sat solver
 *
 *
 * it can load an expression from a FeatureExpr or from a file in CNF
 * format
 */
class BDDFeatureModel(val variables: Map[String, Int], val clauses: IVec[IVecInt], val lastVarId: Int, val extraConstraints: FeatureExpr) extends AbstractFeatureModel with SATBasedFeatureModel {
    /**
     * make the feature model stricter by a formula
     */
    def and(expr: FeatureExpr /*CNF*/): FeatureModel =
        new BDDFeatureModel(variables, clauses, lastVarId, extraConstraints and expr)
}

/**
 * empty feature model
 */
object BDDNoFeatureModel extends BDDFeatureModel(Map(), new Vec(), 0, FeatureExpr.base)

/**
 * companion object to create feature models
 */
object BDDFeatureModelLoader extends AbstractFeatureModelLoader {

    /**
     * create a feature model by loading a CNF file
     * (proprietary format used previously by LinuxAnalysis tools)
     */
    def createFromCNFFile(file: String) = {
        var variables: Map[String, Int] = Map()
        var varIdx = 0
        val clauses = new Vec[IVecInt]()

        for (line <- scala.io.Source.fromFile(file).getLines) {
            if ((line startsWith "@ ") || (line startsWith "$ ")) {
                varIdx += 1
                variables = variables.updated("CONFIG_" + line.substring(2), varIdx)
            } else {
                val vec = new VecInt()
                for (literal <- line.split(" "))
                    vec.push(lookupLiteral(literal, variables))
                clauses.push(vec)
            }

        }
        new FeatureModel(variables, clauses, varIdx, FeatureExpr.base)
    }

    /**
     * load a standard Dimacs file as feature model
     */
    def createFromDimacsFile(file: String) = {
        var variables: Map[String, Int] = Map()
        val clauses = new Vec[IVecInt]()
        var maxId = 0

        for (line <- scala.io.Source.fromFile(file).getLines) {
            if (line startsWith "c ") {
                val entries = line.substring(2).split(" ")
                val id = if (entries(0) endsWith "$")
                    entries(0).substring(0, entries(0).length - 1).toInt
                else
                    entries(0).toInt
                maxId = scala.math.max(id, maxId)
                variables = variables.updated("CONFIG_" + entries(1), id)
            } else if ((line startsWith "p ") || (line.trim.size == 0)) {
                //comment, do nothing
            } else {
                val vec = new VecInt()
                for (literal <- line.split(" "))
                    if (literal != "0")
                        vec.push(literal.toInt)
                clauses.push(vec)
            }

        }
        assert(maxId == variables.size)
        new FeatureModel(variables, clauses, maxId, FeatureExpr.base)
    }
    /**
     * special reader for the -2var model used by the LinuxAnalysis tools from waterloo
     */
    def createFromDimacsFile_2Var(file: String) = {
        var variables: Map[String, Int] = Map()
        val clauses = new Vec[IVecInt]()
        var maxId = 0

        for (line <- scala.io.Source.fromFile(file).getLines) {
            if (line startsWith "c ") {
                val entries = line.substring(2).split(" ")
                val id = if (entries(0) endsWith "$")
                    entries(0).substring(0, entries(0).length - 1).toInt
                else
                    entries(0).toInt
                maxId = scala.math.max(id, maxId)
                val varname = "CONFIG_" + (/*if (entries(1).endsWith("_m")) entries(1).substring(0, entries(1).length - 2)+"_MODULE" else*/ entries(1))
                if (variables contains varname)
                    assert(false, "variable " + varname + " declared twice")
                variables = variables.updated(varname, id)
            } else if ((line startsWith "p ") || (line.trim.size == 0)) {
                //comment, do nothing
            } else {
                val vec = new VecInt()
                for (literal <- line.split(" "))
                    if (literal != "0")
                        vec.push(literal.toInt)
                clauses.push(vec)
            }

        }
        assert(maxId == variables.size)
        new FeatureModel(variables, clauses, maxId, FeatureExpr.base)
    }

    private def lookupLiteral(literal: String, variables: Map[String, Int]) =
        if (literal.startsWith("-"))
            -variables.getOrElse("CONFIG_" + (literal.substring(1)), throw new Exception("variable not declared"))
        else
            variables.getOrElse("CONFIG_" + literal, throw new Exception("variable not declared"))


    //        private[FeatureModel] def getVariables(expr: FeatureExpr /*CNF*/ , lastVarId: Int, oldMap: Map[String, Int] = Map()): (Map[String, Int], Int) = {
    //            val uniqueFlagIds = mutable.Map[String, Int]()
    //            uniqueFlagIds ++= oldMap
    //            var lastId = lastVarId
    //
    //            for (clause <- CNFHelper.getCNFClauses(expr))
    //                for (literal <- CNFHelper.getDefinedExprs(clause))
    //                    if (!uniqueFlagIds.contains(literal.satName)) {
    //                        lastId = lastId + 1
    //                        uniqueFlagIds(literal.satName) = lastId
    //                    }
    //            (immutable.Map[String, Int]() ++ uniqueFlagIds, lastId)
    //        }
    //
    //
    //        private[FeatureModel] def addClauses(cnf: FeatureExpr /*CNF*/ , variables: Map[String, Int], oldVec: IVec[IVecInt] = null): Vec[IVecInt] = {
    //            val result = new Vec[IVecInt]()
    //            if (oldVec != null)
    //                oldVec.copyTo(result)
    //            for (clause <- CNFHelper.getCNFClauses(cnf); if (clause != True))
    //                result.push(SatSolver.getClauseVec(variables, clause))
    //            result
    //        }
}