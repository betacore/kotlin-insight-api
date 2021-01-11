package com.coop.technologies.kotlinInsightApi

sealed class Either<out A, out B> {
    data class Right<A, B>(val b: B) : Either<A, B>()
    data class Left<A, B>(val a: A) : Either<A, B>()
}

fun <A, B> A.left(): Either<A, B> =
    Either.Left(this)

fun <A, B> B.right(): Either<A, B> =
    Either.Right(this)

inline fun <A, B, C> Either<A, B>.map(f: (B) -> C): Either<A, C> =
    when (this) {
        is Either.Right -> f(this.b).right()
        is Either.Left -> this.a.left()
    }

inline fun <A, B, C> Either<A, B>.bind(f: (B) -> Either<A, C>): Either<A, C> =
    when (this) {
        is Either.Right -> f(this.b)
        is Either.Left -> this.a.left()
    }

fun <A, B, C> Either<A, B>.ap(f: Either<A, (B) -> C>): Either<A, C> =
    when (this) {
        is Either.Right -> f.map { it(this.b) }
        is Either.Left -> this.a.left()
    }

fun <A, B, C, D> liftA2(a: Either<A, B>, b: Either<A, C>, f: (B) -> (C) -> D): Either<A, D> =
    b.ap(a.map(f))

fun <A, B> List<Either<A, B>>.sequence(): Either<A, List<B>> {
    if (isEmpty()) return emptyList<B>().right()
    return first().bind { head ->
        liftA2(listOf(head).right(), this.drop(1).sequence()) { a: List<B> -> { b: List<B> -> a + b } }
    }
}
