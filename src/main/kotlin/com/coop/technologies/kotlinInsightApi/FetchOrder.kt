package com.coop.technologies.kotlinInsightApi

import com.linkedplanet.lib.graphlib.Tree
import com.linkedplanet.lib.graphlib.graphtypes.DirectedGraph

fun <A : InsightEntity> buildFetchList(clazz: Class<A>): List<Class<*>>  =
    buildTypeTree(clazz).toGraph().invert().getContainedTrees().filterNotNull().fetchOrder()

private fun <A : InsightEntity> buildTypeTree(clazz: Class<A>): Tree<Class<*>> {
    val fieldsMap = clazz.declaredFields.map {
        it.name.capitalize() to it.type
    }.filter { (_, subclazz) ->
        subclazz.interfaces.contains(InsightEntity::class.java)
    }.map { (name, subclazz) ->
        name to buildTypeTree(subclazz as Class<InsightEntity>)
    }.toMap()

    return if (fieldsMap.isEmpty()) Tree(clazz, null)
    else Tree(clazz, fieldsMap.map { it.value })
}

private fun <A> List<Tree<A>>.fetchOrder(): List<A> {
    if (isEmpty()) return emptyList()

    val heads = treeHeads()
    val tails = treeTails()
    return heads.filter { item -> !tails.any { it.nodes().contains(item) } } +
            tails.fetchOrder()
}

private fun <A> List<Tree<A>>.treeHeads(): List<A> =
    map { it.root }

private fun <A> List<Tree<A>>.treeTails(): List<Tree<A>> =
    flatMap { it.subTrees ?: emptyList() }

private fun <A> Tree<A>.nodes(): List<A> =
    listOf(root) + (subTrees?.flatMap { it.nodes() } ?: emptyList())

private fun <A> Tree<A>.toGraph(): DirectedGraph<A> =
    when (this.subTrees) {
        null -> DirectedGraph(emptyList())
        else -> DirectedGraph(subTrees!!.map { this.root to it.root } +
                subTrees!!.flatMap { it.toGraph().getEdgeList() })
    }

private fun <A> DirectedGraph<A>.invert(): DirectedGraph<A> =
    DirectedGraph(this.getEdgeList().map { Pair(it.second, it.first) })

