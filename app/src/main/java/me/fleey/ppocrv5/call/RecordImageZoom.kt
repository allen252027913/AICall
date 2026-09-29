package me.fleey.ppocrv5.call

class RecordImageZoom {
  var scale = 1f
    private set
  var x = 0f
    private set
  var y = 0f
    private set

  fun transform(zoom: Float, panX: Float, panY: Float, focusX: Float, focusY: Float,
    width: Float, height: Float, imageWidth: Float, imageHeight: Float) {
    if (!zoom.isFinite() || zoom <= 0f || width <= 0f || height <= 0f) return
    val nextScale = (scale * zoom).coerceIn(1f, 5f)
    val ratio = nextScale / scale
    val focusFromCenterX = focusX - width / 2f
    val focusFromCenterY = focusY - height / 2f
    val maxX = ((imageWidth * nextScale - width) / 2f).coerceAtLeast(0f)
    val maxY = ((imageHeight * nextScale - height) / 2f).coerceAtLeast(0f)
    x = (focusFromCenterX - (focusFromCenterX - x) * ratio + panX).coerceIn(-maxX, maxX)
    y = (focusFromCenterY - (focusFromCenterY - y) * ratio + panY).coerceIn(-maxY, maxY)
    scale = nextScale
  }
}
