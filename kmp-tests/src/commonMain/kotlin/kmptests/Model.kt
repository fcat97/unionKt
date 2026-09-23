package kmptests

import com.github.fcat97.unionkt.Derive
import com.github.fcat97.unionkt.Union
import kotlinx.serialization.Serializable

@Serializable
data class User(val name: String)

@Serializable
data class Circle(val radius: Int)

@Serializable
data class Square(val side: Int)

@Union(Int::class, String::class, User::class)
interface ResultSpec

@Union
interface EitherSpec<L, R>

@Union(Circle::class, Square::class)
interface ShapeSpec

@Union(Int::class, ShapeSpec::class)
interface ItemSpec

@Derive
sealed interface UiState {
    data object Loading : UiState
    data class Loaded(val count: Int) : UiState
}

@Derive
sealed interface Box<out T> {
    data class Tagged<out T, out Tag>(val value: T, val tag: Tag) : Box<T>
    data class Many<out E>(val items: List<E>) : Box<List<E>>
}
