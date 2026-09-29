package me.fleey.ppocrv5.call

data class ProductSummary(
  val title: String,
  val price: String,
)

sealed interface ProductRecognitionResult {
  data class Success(val summary: ProductSummary) : ProductRecognitionResult
  data object NoProduct : ProductRecognitionResult
  data object Failure : ProductRecognitionResult
  data class Unavailable(val reason: String) : ProductRecognitionResult
}

fun interface ProductRecognitionGateway {
  fun recognize(request: ProductRecognitionRequest): ProductRecognitionResult
}

/** Process-wide injection seam. Standalone builds always start with the no-network adapter. */
object ProductRecognitionGatewayProvider {
  private val DEFAULT_FACTORY: () -> ProductRecognitionGateway = {
    UnavailableProductRecognitionGateway
  }

  @Volatile
  private var factory: () -> ProductRecognitionGateway = DEFAULT_FACTORY

  fun create(): ProductRecognitionGateway = factory()

  @Synchronized
  fun install(newFactory: () -> ProductRecognitionGateway) {
    factory = newFactory
  }

  @Synchronized
  fun resetToDefault() {
    factory = DEFAULT_FACTORY
  }

}
