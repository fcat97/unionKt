package com.github.fcat97.unionkt.serialization.kotlinx

import com.tschuchort.compiletesting.SourceFile
import kotlin.test.Test
import kotlin.test.assertContains

/**
 * Runtime behaviour of the generated serializers. Every test compiles real code with the
 * serialization compiler plugin and runs its `verify()`.
 *
 * The embedded sources avoid `$` templates: they sit inside this file's raw strings.
 */
class KotlinxSerializationTest {

    private fun run(fileName: String, source: String) {
        compileWithSerialization(SourceFile.kotlin(fileName, source))
            .assertSucceeded()
            .call("test." + fileName.removeSuffix(".kt") + "Kt", "verify")
    }

    @Test
    fun `the union is annotated and a serializer object is generated`() {
        val result = compileWithSerialization(
            SourceFile.kotlin(
                "Plain.kt",
                """
                package test

                import com.github.fcat97.unionkt.Union

                @Union(Int::class, String::class)
                interface PlainSpec
                """.trimIndent(),
            ),
        ).assertSucceeded()

        assertContains(result.generated("Plain.kt"), "@Serializable(with = PlainSerializer::class)")
        assertContains(result.generated("PlainSerializer.kt"), "public object PlainSerializer : KSerializer<Plain>")
    }

    @Test
    fun `primitives, objects and arrays round-trip untagged`() = run(
        "Values.kt",
        """
        package test

        import com.github.fcat97.unionkt.Union
        import kotlinx.serialization.Serializable
        import kotlinx.serialization.encodeToString
        import kotlinx.serialization.json.Json

        @Serializable
        data class User(val name: String)

        @Union(Int::class, String::class, Boolean::class, Double::class, User::class, IntArray::class)
        interface ValueSpec

        fun roundTrip(value: Value, expected: String) {
            val text = Json.encodeToString<Value>(value)
            check(text == expected) { "encoded " + value + " as " + text }
            val back = Json.decodeFromString<Value>(text)
            check(back == value) { "decoded " + text + " as " + back }
        }

        fun verify() {
            roundTrip(Value(5), "5")
            roundTrip(Value("hi"), "\"hi\"")
            roundTrip(Value(true), "true")
            roundTrip(Value(5.5), "5.5")
            roundTrip(Value(User("Ada")), "{\"name\":\"Ada\"}")
            check(Json.encodeToString<Value>(Value(intArrayOf(1, 2))) == "[1,2]")
            val array = Json.decodeFromString<Value>("[1,2]")
            check(array is Value.OnIntArray && array.value.toList() == listOf(1, 2)) { array.toString() }
        }
        """.trimIndent(),
    )

    @Test
    fun `enums and value classes are matched by the kind they serialize as`() = run(
        "Measure.kt",
        """
        package test

        import com.github.fcat97.unionkt.Union
        import kotlinx.serialization.Serializable
        import kotlinx.serialization.encodeToString
        import kotlinx.serialization.json.Json

        enum class Color { RED, GREEN }

        @Serializable
        @JvmInline
        value class Meters(val value: Double)

        @Union(Color::class, Meters::class)
        interface MeasureSpec

        fun verify() {
            check(Json.decodeFromString<Measure>("\"RED\"") == Measure(Color.RED))
            check(Json.decodeFromString<Measure>("2.5") == Measure(Meters(2.5)))
            check(Json.encodeToString<Measure>(Measure(Meters(2.5))) == "2.5")
        }
        """.trimIndent(),
    )

    @Test
    fun `a generic union works as a property and at top level`() = run(
        "Generic.kt",
        """
        package test

        import com.github.fcat97.unionkt.Union
        import kotlinx.serialization.Serializable
        import kotlinx.serialization.encodeToString
        import kotlinx.serialization.json.Json

        @Union
        interface EitherSpec<L, R>

        @Serializable
        data class Holder(val e: Either<String, Int>)

        fun verify() {
            check(Json.encodeToString(Holder(Either.onR(5))) == "{\"e\":5}")
            check(Json.decodeFromString<Holder>("{\"e\":\"x\"}") == Holder(Either.onL("x")))
            check(Json.decodeFromString<Either<String, Int>>("5") == Either.onR(5))
        }
        """.trimIndent(),
    )

    @Test
    fun `flattened and nested unions decode`() = run(
        "Shapes.kt",
        """
        package test

        import com.github.fcat97.unionkt.Union
        import kotlinx.serialization.Serializable
        import kotlinx.serialization.encodeToString
        import kotlinx.serialization.json.Json

        @Serializable
        data class Circle(val radius: Int)

        @Serializable
        data class Square(val side: Int)

        @Union(Circle::class, Square::class)
        interface ShapeSpec

        @Union(Int::class, ShapeSpec::class)
        interface ItemSpec

        @Union(String::class, Shape::class)
        interface NestSpec

        fun verify() {
            check(Json.decodeFromString<Item>("{\"side\":2}") == Item(Square(2)))
            check(Json.decodeFromString<Nest>("{\"radius\":1}") == Nest(Shape(Circle(1))))
            check(Json.encodeToString<Nest>(Nest(Shape(Circle(1)))) == "{\"radius\":1}")
            check(Json.decodeFromString<Nest>("\"text\"") == Nest("text"))
        }
        """.trimIndent(),
    )

    @Test
    fun `a quoted number stays a string`() = run(
        "Text.kt",
        """
        package test

        import com.github.fcat97.unionkt.Union
        import kotlinx.serialization.json.Json

        @Union(Int::class, String::class)
        interface TextSpec

        fun verify() {
            check(Json.decodeFromString<Text>("\"5\"") == Text("5"))
            check(Json.decodeFromString<Text>("5") == Text(5))
        }
        """.trimIndent(),
    )

    @Test
    fun `declaration order decides between object cases`() = run(
        "Order.kt",
        """
        package test

        import com.github.fcat97.unionkt.Union
        import kotlinx.serialization.Serializable
        import kotlinx.serialization.json.Json

        @Serializable
        data class User(val name: String)

        @Serializable
        data class Admin(val name: String, val level: Int)

        @Union(User::class, Admin::class)
        interface UserFirstSpec

        @Union(Admin::class, User::class)
        interface AdminFirstSpec

        fun verify() {
            val lenient = Json { ignoreUnknownKeys = true }
            val bob = "{\"name\":\"Bob\",\"level\":3}"
            check(lenient.decodeFromString<UserFirst>(bob) == UserFirst(User("Bob")))
            check(lenient.decodeFromString<AdminFirst>(bob) == AdminFirst(Admin("Bob", 3)))
            check(lenient.decodeFromString<AdminFirst>("{\"name\":\"Ada\"}") == AdminFirst(User("Ada")))
        }
        """.trimIndent(),
    )

    @Test
    fun `input that matches no case fails with a clear message`() = run(
        "Pick.kt",
        """
        package test

        import com.github.fcat97.unionkt.Union
        import kotlinx.serialization.SerializationException
        import kotlinx.serialization.Serializable
        import kotlinx.serialization.json.Json

        @Serializable
        data class User(val name: String)

        @Union(Int::class, User::class)
        interface PickSpec

        @Serializable
        data class Required(val p: Pick)

        @Serializable
        data class Optional(val p: Pick? = null)

        fun messageOf(block: () -> Unit): String {
            val error = runCatching(block).exceptionOrNull()
            check(error is SerializationException) { "expected a SerializationException, got " + error }
            return error.message.orEmpty()
        }

        fun verify() {
            val noShape = messageOf { Json.decodeFromString<Pick>("\"text\"") }
            check("Cannot decode a string as Pick: no case accepts a string" in noShape) { noShape }

            val tried = messageOf { Json.decodeFromString<Pick>("{\"x\":1}") }
            check("Cannot decode an object as Pick: tried OnUser:" in tried) { tried }

            val nullValue = messageOf { Json.decodeFromString<Required>("{\"p\":null}") }
            check("Cannot decode null as Pick" in nullValue) { nullValue }

            check(Json.decodeFromString<Optional>("{\"p\":null}") == Optional(null))
        }
        """.trimIndent(),
    )

    @Test
    fun `a non-JSON decoder is rejected`() = run(
        "NotJson.kt",
        """
        @file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

        package test

        import com.github.fcat97.unionkt.Union
        import kotlinx.serialization.SerializationException
        import kotlinx.serialization.descriptors.SerialDescriptor
        import kotlinx.serialization.encoding.AbstractDecoder
        import kotlinx.serialization.encoding.CompositeDecoder
        import kotlinx.serialization.modules.EmptySerializersModule
        import kotlinx.serialization.modules.SerializersModule

        @Union(Int::class)
        interface OnlySpec

        class NotJson : AbstractDecoder() {
            override val serializersModule: SerializersModule = EmptySerializersModule()
            override fun decodeElementIndex(descriptor: SerialDescriptor): Int = CompositeDecoder.DECODE_DONE
        }

        fun verify() {
            val error = runCatching { OnlySerializer.deserialize(NotJson()) }.exceptionOrNull()
            check(error is SerializationException) { "got " + error }
            check("OnlySerializer supports JSON only" in error.message.orEmpty()) { error.message.orEmpty() }
        }
        """.trimIndent(),
    )
}
