package me.fleey.ppocrv5.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProductRecognitionResponseParserTest {
  @Test
  fun `full response returns only the first product`() {
    val json = """
      {
        "data":{"resultValue":{"32076707":{"data":[{"productList":[
          {"titleNew":" 晨光易擦白板笔 ","shortTitle":"白板笔","priceIntString":" 8 ","priceDecimalString":" .89 "},
          {"titleNew":"不应播报","priceIntString":"99","priceDecimalString":".00"}
        ]}]}}}
      }
    """.trimIndent()

    assertEquals(
      ProductSummary("晨光易擦白板笔", "8.89"),
      ProductRecognitionResponseParser.parseFirstProduct(json),
    )
  }

  @Test
  fun `data node response falls back to trimmed short title`() {
    val json = """
      {"resultValue":{"32076707":{"data":[{"productList":[{
        "titleNew":" ","shortTitle":" 晨光白板笔 ","priceIntString":"12","priceDecimalString":""
      }]}]}}}
    """.trimIndent()

    assertEquals(
      ProductRecognitionParseResult.Success(ProductSummary("晨光白板笔", "12")),
      ProductRecognitionResponseParser.parse(json),
    )
  }

  @Test
  fun `empty or missing product list returns no product`() {
    val empty = """{"data":{"resultValue":{"32076707":{"data":[{"productList":[]}]}}}}"""
    val missing = """{"resultValue":{"32076707":{"data":[{}]}}}"""

    assertEquals(ProductRecognitionParseResult.NoProduct, ProductRecognitionResponseParser.parse(empty))
    assertEquals(ProductRecognitionParseResult.NoProduct, ProductRecognitionResponseParser.parse(missing))
    assertNull(ProductRecognitionResponseParser.parseFirstProduct(empty))
  }

  @Test
  fun `malformed structure missing title or missing price is invalid`() {
    val missingTitle = """{"resultValue":{"32076707":{"data":[{"productList":[{
      "priceIntString":"8","priceDecimalString":".89"
    }]}]}}}"""
    val missingPrice = """{"resultValue":{"32076707":{"data":[{"productList":[{
      "titleNew":"白板笔"
    }]}]}}}"""

    assertEquals(ProductRecognitionParseResult.Invalid, ProductRecognitionResponseParser.parse("not-json"))
    assertEquals(ProductRecognitionParseResult.Invalid, ProductRecognitionResponseParser.parse("{}"))
    assertEquals(ProductRecognitionParseResult.Invalid, ProductRecognitionResponseParser.parse(missingTitle))
    assertEquals(ProductRecognitionParseResult.Invalid, ProductRecognitionResponseParser.parse(missingPrice))
  }
}
