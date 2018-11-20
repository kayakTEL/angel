package com.tencent.angel.ml.tree

import com.tencent.angel.ml.math2.VFactory
import com.tencent.angel.ml.math2.vector.{IntDoubleVector, IntFloatVector}
import com.tencent.angel.ml.tree.DecisionTreeModelReadWrite.NodeData
import com.tencent.angel.ml.tree.model._
import com.tencent.angel.ml.tree.impurity.ImpurityCalculator
import com.tencent.angel.ml.tree.oldmodel.{DecisionTreeModel => OldDecisionTreeModel}

import scala.collection.mutable
import scala.reflect.ClassTag

/**
  * Abstraction for Decision Tree models.
  */
trait DecisionTreeModel {

  /** Root of the decision tree */
  def rootNode: Node

  /** Number of nodes in tree, including leaf nodes. */
  def numNodes: Int = {
    1 + rootNode.numDescendants
  }

  /**
    * Depth of the tree.
    * E.g.: Depth 0 means 1 leaf node.  Depth 1 means 1 internal node and 2 leaf nodes.
    */
  lazy val depth: Int = {
    rootNode.subtreeDepth
  }

  /** Summary of the model */
  override def toString: String = {
    // Implementing classes should generally override this method to be more descriptive.
    s"DecisionTreeModel of depth $depth with $numNodes nodes"
  }

  /** Full description of model */
  def toDebugString: String = {
    val header = toString + "\n"
    header + rootNode.subtreeToString(2)
  }

  /**
    * Trace down the tree, and return the largest feature index used in any split.
    *
    * @return  Max feature index used in a split, or -1 if there are no splits (single leaf node).
    */
  def maxSplitFeatureIndex(): Int = rootNode.maxSplitFeatureIndex()

  /** Convert to spark.mllib DecisionTreeModel (losing some information) */
  private[tree] def toOld: OldDecisionTreeModel
}

/**
  * Abstraction for models which are ensembles of decision trees
  *
  * TODO: Add support for predicting probabilities and raw predictions  SPARK-3727
  *
  * @tparam M  Type of tree model in this ensemble
  */
private[tree] trait TreeEnsembleModel[M <: DecisionTreeModel] {

  // Note: We use getTrees since subclasses of TreeEnsembleModel will store subclasses of
  //       DecisionTreeModel.

  /** Trees in this ensemble. Warning: These have null parent Estimators. */
  def trees: Array[M]

  /** Weights for each tree, zippable with [[trees]] */
  def treeWeights: Array[Double]

  def treeWeightsVec: IntDoubleVector = VFactory.denseDoubleVector(treeWeights)

  /** Summary of the model */
  override def toString: String = {
    // Implementing classes should generally override this method to be more descriptive.
    s"TreeEnsembleModel with ${trees.length} trees"
  }

  /** Full description of model */
  def toDebugString: String = {
    val header = toString + "\n"
    header + trees.zip(treeWeights).zipWithIndex.map { case ((tree, weight), treeIndex) =>
      s"  Tree $treeIndex (weight $weight):\n" + tree.rootNode.subtreeToString(4)
    }.fold("")(_ + _)
  }

  /** Total number of nodes, summed over all trees in the ensemble. */
  lazy val totalNumNodes: Int = trees.map(_.numNodes).sum
}

private[tree] object TreeEnsembleModel {

  /**
    * Given a tree ensemble model, compute the importance of each feature.
    * This generalizes the idea of "Gini" importance to other losses,
    * following the explanation of Gini importance from "Random Forests" documentation
    * by Leo Breiman and Adele Cutler, and following the implementation from scikit-learn.
    *
    * For collections of trees, including boosting and bagging, Hastie et al.
    * propose to use the average of single tree importances across all trees in the ensemble.
    *
    * This feature importance is calculated as follows:
    *  - Average over trees:
    *     - importance(feature j) = sum (over nodes which split on feature j) of the gain,
    *       where gain is scaled by the number of instances passing through node
    *     - Normalize importances for tree to sum to 1.
    *  - Normalize feature importance vector to sum to 1.
    *
    *  References:
    *  - Hastie, Tibshirani, Friedman. "The Elements of Statistical Learning, 2nd Edition." 2001.
    *
    * @param trees  Unweighted collection of trees
    * @param numFeatures  Number of features in model (even if not all are explicitly used by
    *                     the model).
    *                     If -1, then numFeatures is set based on the max feature index in all trees.
    * @return  Feature importance values, of length numFeatures.
    */
  def featureImportances[M <: DecisionTreeModel](trees: Array[M], numFeatures: Int): IntFloatVector = {
    val totalImportances = mutable.Map[Int, Float]()
    trees.foreach { tree =>
      // Aggregate feature importance vector for this tree
      val importances = mutable.Map[Int, Float]()
      computeFeatureImportance(tree.rootNode, importances)
      // Normalize importance vector for this tree, and add it to total.
      // TODO: In the future, also support normalizing by tree.rootNode.impurityStats.count?
      val treeNorm = importances.values.sum
      if (treeNorm != 0) {
        importances.foreach { case (idx, impt) =>
          val normImpt = impt / treeNorm
          if (!totalImportances.contains(idx)) totalImportances += (idx -> normImpt)
          else totalImportances.update(idx, totalImportances(idx) + normImpt)
        }
      }
    }
    // Normalize importances
    normalizeMapValues(totalImportances)
    // Construct vector
    val d = if (numFeatures != -1) {
      numFeatures
    } else {
      // Find max feature index used in trees
      val maxFeatureIndex = trees.map(_.maxSplitFeatureIndex()).max
      maxFeatureIndex + 1
    }
    if (d == 0) {
      assert(totalImportances.isEmpty, s"Unknown error in computing feature" +
        s" importance: No splits found, but some non-zero importances.")
    }
    val (indices, values) = totalImportances.iterator.toSeq.sortBy(_._1).unzip
    VFactory.sparseFloatVector(d, indices.toArray, values.toArray)
  }

  /**
    * Given a Decision Tree model, compute the importance of each feature.
    * This generalizes the idea of "Gini" importance to other losses,
    * following the explanation of Gini importance from "Random Forests" documentation
    * by Leo Breiman and Adele Cutler, and following the implementation from scikit-learn.
    *
    * This feature importance is calculated as follows:
    *  - importance(feature j) = sum (over nodes which split on feature j) of the gain,
    *    where gain is scaled by the number of instances passing through node
    *  - Normalize importances for tree to sum to 1.
    *
    * @param tree  Decision tree to compute importances for.
    * @param numFeatures  Number of features in model (even if not all are explicitly used by
    *                     the model).
    *                     If -1, then numFeatures is set based on the max feature index in all trees.
    * @return  Feature importance values, of length numFeatures.
    */
  def featureImportances[M <: DecisionTreeModel : ClassTag](tree: M, numFeatures: Int): IntFloatVector = {
    featureImportances(Array(tree), numFeatures)
  }

  /**
    * Recursive method for computing feature importances for one tree.
    * This walks down the tree, adding to the importance of 1 feature at each node.
    *
    * @param node  Current node in recursion
    * @param importances  Aggregate feature importances, modified by this method
    */
  def computeFeatureImportance(
                                node: Node,
                                importances: mutable.Map[Int, Float]): Unit = {
    node match {
      case n: InternalNode =>
        val feature = n.split.featureIndex
        val scaledGain = n.gain * n.impurityStats.count
        if (!importances.contains(feature)) importances += (feature -> scaledGain)
        else importances.update(feature, importances(feature) + scaledGain)
        computeFeatureImportance(n.leftChild, importances)
        computeFeatureImportance(n.rightChild, importances)
      case n: LeafNode =>
      // do nothing
    }
  }

  /**
    * Normalize the values of this map to sum to 1, in place.
    * If all values are 0, this method does nothing.
    *
    * @param map  Map with non-negative values.
    */
  def normalizeMapValues(map: mutable.Map[Int, Float]): Unit = {
    val total = map.values.sum
    if (total != 0) {
      val keys = map.iterator.map(_._1).toArray
      keys.foreach { key =>
        if (!map.contains(key)) map += (key -> 0.0f) else map.update(key, map(key) / total)
      }
    }
  }
}

/** Helper classes for tree model persistence */
private[tree] object DecisionTreeModelReadWrite {

  /**
    * Info for a [[org.apache.spark.ml.tree.Split]]
    *
    * @param featureIndex  Index of feature split on
    * @param leftCategoriesOrThreshold  For categorical feature, set of leftCategories.
    *                                   For continuous feature, threshold.
    * @param numCategories  For categorical feature, number of categories.
    *                       For continuous feature, -1.
    */
  case class SplitData(
                        featureIndex: Int,
                        leftCategoriesOrThreshold: Array[Float],
                        numCategories: Int) {

    def getSplit: Split = {
      if (numCategories != -1) {
        new CategoricalSplit(featureIndex, leftCategoriesOrThreshold, numCategories)
      } else {
        assert(leftCategoriesOrThreshold.length == 1, s"DecisionTree split data expected" +
          s" 1 threshold for ContinuousSplit, but found thresholds: " +
          leftCategoriesOrThreshold.mkString(", "))
        new ContinuousSplit(featureIndex, leftCategoriesOrThreshold(0))
      }
    }
  }

  object SplitData {
    def apply(split: Split): SplitData = split match {
      case s: CategoricalSplit =>
        SplitData(s.featureIndex, s.leftCategories, s.numCategories)
      case s: ContinuousSplit =>
        SplitData(s.featureIndex, Array(s.threshold), -1)
    }
  }

  /**
    * Info for a [[Node]]
    *
    * @param id  Index used for tree reconstruction.  Indices follow a pre-order traversal.
    * @param impurityStats  Stats array.  Impurity type is stored in metadata.
    * @param gain  Gain, or arbitrary value if leaf node.
    * @param leftChild  Left child index, or arbitrary value if leaf node.
    * @param rightChild  Right child index, or arbitrary value if leaf node.
    * @param split  Split info, or arbitrary value if leaf node.
    */
  case class NodeData(
                       id: Int,
                       prediction: Float,
                       impurity: Float,
                       impurityStats: Array[Float],
                       gain: Float,
                       leftChild: Int,
                       rightChild: Int,
                       split: SplitData)

  object NodeData {
    /**
      * Create [[NodeData]] instances for this node and all children.
      *
      * @param id  Current ID.  IDs are assigned via a pre-order traversal.
      * @return (sequence of nodes in pre-order traversal order, largest ID in subtree)
      *         The nodes are returned in pre-order traversal (root first) so that it is easy to
      *         get the ID of the subtree's root node.
      */
    def build(node: Node, id: Int): (Seq[NodeData], Int) = node match {
      case n: InternalNode =>
        val (leftNodeData, leftIdx) = build(n.leftChild, id + 1)
        val (rightNodeData, rightIdx) = build(n.rightChild, leftIdx + 1)
        val thisNodeData = NodeData(id, n.prediction, n.impurity, n.impurityStats.stats,
          n.gain, leftNodeData.head.id, rightNodeData.head.id, SplitData(n.split))
        (thisNodeData +: (leftNodeData ++ rightNodeData), rightIdx)
      case _: LeafNode =>
        (Seq(NodeData(id, node.prediction, node.impurity, node.impurityStats.stats,
          -1.0f, -1, -1, SplitData(-1, Array.empty[Float], -1))),
          id)
    }
  }

  /**
    * Given all data for all nodes in a tree, rebuild the tree.
    * @param data  Unsorted node data
    * @param impurityType  Impurity type for this tree
    * @return Root node of reconstructed tree
    */
  def buildTreeFromNodes(data: Array[NodeData], impurityType: String): Node = {
    // Load all nodes, sorted by ID.
    val nodes = data.sortBy(_.id)
    // Sanity checks; could remove
    assert(nodes.head.id == 0, s"Decision Tree load failed.  Expected smallest node ID to be 0," +
      s" but found ${nodes.head.id}")
    assert(nodes.last.id == nodes.length - 1, s"Decision Tree load failed.  Expected largest" +
      s" node ID to be ${nodes.length - 1}, but found ${nodes.last.id}")
    // We fill `finalNodes` in reverse order.  Since node IDs are assigned via a pre-order
    // traversal, this guarantees that child nodes will be built before parent nodes.
    val finalNodes = new Array[Node](nodes.length)
    nodes.reverseIterator.foreach { case n: NodeData =>
      val impurityStats = ImpurityCalculator.getCalculator(impurityType, n.impurityStats)
      val node = if (n.leftChild != -1) {
        val leftChild = finalNodes(n.leftChild)
        val rightChild = finalNodes(n.rightChild)
        new InternalNode(n.prediction, n.impurity, n.gain, leftChild, rightChild,
          n.split.getSplit, impurityStats)
      } else {
        new LeafNode(n.prediction, n.impurity, impurityStats)
      }
      finalNodes(n.id) = node
    }
    // Return the root node
    finalNodes.head
  }
}

private[tree] object EnsembleModelReadWrite {

  /**
    * Info for one [[Node]] in a tree ensemble
    *
    * @param treeID  Tree index
    * @param nodeData  Data for this node
    */
  case class EnsembleNodeData(
                               treeID: Int,
                               nodeData: NodeData)

  object EnsembleNodeData {
    /**
      * Create [[EnsembleNodeData]] instances for the given tree.
      *
      * @return Sequence of nodes for this tree
      */
    def build(tree: DecisionTreeModel, treeID: Int): Seq[EnsembleNodeData] = {
      val (nodeData: Seq[NodeData], _) = NodeData.build(tree.rootNode, 0)
      nodeData.map(nd => EnsembleNodeData(treeID, nd))
    }
  }
}
