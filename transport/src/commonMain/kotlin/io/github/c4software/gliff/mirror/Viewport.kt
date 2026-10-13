package io.github.c4software.gliff.mirror

/** The window of a web mirror's page: `width x height` physical pixels at display scale `scale`. */
data class Viewport(val width: Int, val height: Int, val scale: Float)
