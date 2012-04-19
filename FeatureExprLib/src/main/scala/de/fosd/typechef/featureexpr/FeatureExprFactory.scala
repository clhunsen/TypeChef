package de.fosd.typechef.featureexpr

import bdd.BDDFeatureExprFactory
import sat.SATFeatureExprFactory


/**
 * with this factory you can create new feature expressions.
 *
 * most importantly createDefinedExternal creates a new atomic feature and True and False are atoms.
 *
 * the FeatureExprFactory supports different implementations. Objects of different implementations may not be mixed
 * (this is currently not statically ensured due to problems with crossing to legacy Java code)
 */
object FeatureExprFactory {

    var default: AbstractFeatureExprFactory = if (System.getProperty("FEATUREEXPR") == "BDD") bdd else sat
    def dflt = default

    def setDefault(newFactory: AbstractFeatureExprFactory) {
        default = newFactory
    }


    lazy val bdd: AbstractFeatureExprFactory = BDDFeatureExprFactory
    lazy val sat: AbstractFeatureExprFactory = SATFeatureExprFactory


    //shorthands for convenience
    def createDefinedExternal(featureName: String) = default.createDefinedExternal(featureName)
    def True: FeatureExpr = default.True
    def False: FeatureExpr = default.False

}

trait AbstractFeatureExprFactory extends FeatureExprTreeFactory {
    def createDefinedExternal(v: String): FeatureExpr
    def createDefinedMacro(name: String, macroTable: FeatureProvider): FeatureExpr

    def True: FeatureExpr
    def False: FeatureExpr

    def featureModelFactory: FeatureModelFactory
}