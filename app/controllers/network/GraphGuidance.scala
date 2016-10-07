/*

 */
package controllers.network

import java.util.NoSuchElementException

import model.faceted.search.{ FacetedSearch, Facets, NodeBucket }
import play.api.Logger
import util.SessionUtils.currentDataset

import scala.collection.immutable.HashMap
import scala.collection.mutable
import scala.math.Ordering

import model.faceted.search.{ FacetedSearch, Facets, NodeBucket }
import model.{ Entity, EntityType }
import play.api.libs.json.{ JsObject, Json }
import play.api.mvc.{ Action, Controller, Results }
import util.TimeRangeParser
import util.SessionUtils.currentDataset

/**
 * Created by martin on 02.10.16.
 */

class GraphGuidance() {
  implicit val context = this

  val typeIndex = collection.immutable.HashMap("PER" -> 0, "ORG" -> 1, "LOC" -> 2, "MISC" -> 3)
  val newEdgesPerIter = 100

  // Gibt an wie viele Iterationen schon vollzogen wurdn
  var iter: Int = 0

  // cacht schon besuchte Knoten
  var nodes = new mutable.HashMap[Long, Node]()
  var usedNodes = new mutable.HashMap[Long, Node]()
  var oldEdges = new mutable.HashMap[(Long, Long), Edge]()

  var uiMatrix = Array.ofDim[Int](4, 4)
  var epn = 4
  var edgeAmount = 20

  var prefferedNodes: List[Long] = List()

  // var uiMatrix = Array.ofDim[Int](4, 4)

  // scalastyle:off
  def getGuidance(focusId: Long, edgeAmount: Int, epn: Int, uiMatrix: Array[Array[Int]], useOldEdges: Boolean, prefferedNodes: List[Long]): Iterator[(Edge, Option[Node])] = {
    this.epn = epn
    this.uiMatrix = uiMatrix
    this.edgeAmount = edgeAmount
    this.prefferedNodes = prefferedNodes

    new Iterator[(Edge, Option[Node])] {
      iter += 1

      if (!useOldEdges) {
        oldEdges.clear
      }

      var startNode = nodes.getOrElseUpdate(focusId, NodeFactory.createNodes(List(focusId), 0, iter).head)
      startNode.update(0, iter)

      Logger.info("start guidance")

      var usedEdges = new mutable.HashMap[(Node, Node), Edge]()
      usedNodes.clear()
      usedNodes += (startNode.getId -> startNode)

      var pq = mutable.PriorityQueue[Edge]()(Ordering.by[Edge, (Double, Int)](e => (
        e.getDoi,
        if (prefferedNodes.exists(l => e.getNodes._1.getId == l || e.getNodes._2.getId == l)) -e.getDist else Int.MinValue
      ))) ++= getEdges(startNode)

      override def hasNext: Boolean = {
        pq.exists(e => !(usedEdges.contains(e.getNodes) || usedEdges.contains(e.getNodes.swap) || e.getNodes._1.getConn == epn || e.getNodes._2.getConn == epn))
      }

      override def next(): (Edge, Option[Node]) = {

        object pqIter extends Iterator[Edge] {
          // ACHTUNG pq gibt mit .find() nicht nach DoI geordnet aus! Deswegen ist das hier notwendig
          def hasNext = pq.nonEmpty
          def next = pq.dequeue
        }
        val edgeO = pqIter.find(e => !{
          Logger.info("" + e.toString(true) + "E1:" + e.getNodes._1.getConn + "E2:" + e.getNodes._2.getConn)
          usedEdges.contains(e.getNodes) || usedEdges.contains(e.getNodes.swap) || e.getNodes._1.getConn == epn || e.getNodes._2.getConn == epn
        })

        val edge = edgeO.getOrElse({
          throw new NoSuchElementException
        })

        edge.getNodes._1.incConn()
        edge.getNodes._2.incConn()

        usedEdges += ((edge.getNodes, edge))

        Logger.info("" + usedEdges)
        Logger.info("added: " + edge.toString(true))

        var newNode: Option[Node] = None
        if (!usedNodes.contains(edge.getNodes._2.getId)) {
          newNode = Some(edge.getNodes._2)
          usedNodes += (edge.getNodes._2.getId -> edge.getNodes._2)
          //if (i < k / 2) { //nur für die ersten k/2 Kanten werden weitere Kanten gesucht
          pq ++= getEdges(edge.getNodes._2)
          //}
        } // else if (!usedNodes.contains(edge.getNodes._1)) {
        // usedNodes += edge.getNodes._1
        //if (i < k / 2) { //nur für die ersten k/2 Kanten werden weitere Kanten gesucht
        // pq ++= getEdges(edge.getNodes._1)
        // }
        // }
        //Logger.info(edgeMap.toString)
        oldEdges += (edge.getNodes._1.getId, edge.getNodes._2.getId) -> edge
        (edge, newNode)
      }

      private def getEdges(node: Node): mutable.MutableList[Edge] = {

        var distToFocus: Int = node.getDistance //Wenn der Knoten nicht in der Map liegt, muss es sich um dem Fokus handeln, also dist=0
        distToFocus += 1
        val nodeBuckets = FacetedSearch.fromIndexName("cable").aggregateEntities(Facets(List(), Map(), List(node.getId), None, None), newEdgesPerIter, List(), Nil, 1).buckets
        val edgeFreqTuple = nodeBuckets.collect { case NodeBucket(id, docOccurrence) => (id, docOccurrence.toInt) }.filter(_._1 != node.getId)
        val nodeMap = NodeFactory.createNodes(edgeFreqTuple.map(_._1), distToFocus, iter).map(n => n.getId -> n).toMap

        var newEdges = new mutable.MutableList[Edge]()
        edgeFreqTuple.foreach(et => {
          if (nodeMap.contains(et._1)) {
            var n = nodeMap(et._1)
            n = nodes.getOrElseUpdate(n.getId, n)
            n.update(distToFocus, iter)
            var oldDoi = 0.0
            if (oldEdges.contains((node.getId, n.getId))) {
              oldDoi = oldEdges((node.getId, n.getId)).getDoi
            } else if (oldEdges.contains((n.getId, node.getId))) {
              oldDoi = oldEdges((n.getId, node.getId)).getDoi
            }
            newEdges += new Edge(node, n, et._2, uiMatrix(typeIndex(node.getCategory))(typeIndex(n.getCategory)), oldDoi)
          }
        })

        //TODO Distanzen müssen rekursiv geupdatet werden
        newEdges
      }
    }
  }

  def getCopyGuidance(focusId: Long, useOldEdges: Boolean): Iterator[(Edge, Option[Node])] = {
    val newGG = new GraphGuidance
    newGG.oldEdges = oldEdges.map(p => p._1 -> p._2.copy)
    newGG.nodes = nodes.map(p => p._1 -> p._2.copy)
    newGG.iter = iter
    newGG.getGuidance(focusId, edgeAmount, epn, uiMatrix, useOldEdges, List())
  }
}
