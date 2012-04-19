package de.fosd.typechef.featureexpr.sat

import de.fosd.typechef.featureexpr._
import java.net.URI

object SATFeatureExprFactory extends AbstractFeatureExprFactory {


    def createDefinedExternal(name: String): FeatureExpr = FExprBuilder.definedExternal(name)
    def createDefinedMacro(name: String, macroTable: FeatureProvider): FeatureExpr = FExprBuilder.definedMacro(name, macroTable)


    //helper
    //        def createIf(condition: FeatureExpr, thenBranch: FeatureExpr, elseBranch: FeatureExpr): FeatureExpr = FeatureExprFactory.createBooleanIf(condition, thenBranch, elseBranch)

    val baseB: SATFeatureExpr = de.fosd.typechef.featureexpr.sat.True
    val deadB: SATFeatureExpr = de.fosd.typechef.featureexpr.sat.False
    val True: FeatureExpr = baseB
    val False: FeatureExpr = deadB


    //feature model stuff
    def featureModelFactory: FeatureModelFactory = SATFeatureModel

}