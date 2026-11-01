class Ep8ModeDataType(
    extension: String,
    private val ep8Manager: ShimanoEp8Manager,
    private val scope: CoroutineScope
) : DataTypeImpl(extension, "ep8-mode") {

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val job = scope.launch {
            ep8Manager.telemetry.collectLatest { state ->
                val views = RemoteViews(context.packageName, R.layout.ep8_mode_view)

                val (modeText, dotColor) = if (!state.connected) {
                    "--" to Color.parseColor("#9E9E9E")
                } else {
                    when (state.assistMode) {
                        1 -> "ECO" to Color.parseColor("#4CAF50")    // Grün
                        2 -> "TRAIL" to Color.parseColor("#2196F3")  // Blau
                        3 -> "BOOST" to Color.parseColor("#FF5722")  // Rot/Orange
                        4 -> "WALK" to Color.parseColor("#FFC107")   // Gelb (Bereitschaft)
                        5 -> "WALK+" to Color.parseColor("#00BCD4")  // Türkis (Motor schiebt aktiv)
                        else -> "OFF" to Color.parseColor("#9E9E9E") // Grau
                    }
                }

                views.setTextViewText(R.id.ep8_mode_dot, "●")
                views.setTextColor(R.id.ep8_mode_dot, dotColor)
                views.setTextViewText(R.id.ep8_mode_text, modeText)

                emitter.updateView(views)
            }
        }
        emitter.setCancellable { job.cancel() }
    }
}
