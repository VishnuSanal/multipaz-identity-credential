package org.multipaz.presentment.model

/**
 * Thrown when no documents exists to satisfy the request
 *
 * @property message message to display.
 */
class PresentmentEmpty(message: String): Exception(message)