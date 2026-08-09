package app.auriel.edenlauncher.allapps

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import app.auriel.edenlauncher.model.AppInfo
import app.auriel.edenlauncher.views.BubbleTextView

/**
 * Adapter for the vertical drawer grid.
 *
 * Ported from `AllAppsGridAdapter` (AOSP 7) minus the section headers and predicted-apps rows.
 * Icons are already rasterised by the loader, so binding is a field assignment, not a decode.
 */
class AppListAdapter(
    private var apps: List<AppInfo>,
    private val onClick: (AppInfo) -> Unit,
    private val onLongClick: (AppInfo, View) -> Unit,
) : RecyclerView.Adapter<AppListAdapter.AppViewHolder>() {

    class AppViewHolder(val icon: BubbleTextView) : RecyclerView.ViewHolder(icon)

    init {
        // Item identity is stable across filtering, which keeps the grid from re-animating rows.
        setHasStableIds(true)
    }

    fun submit(newApps: List<AppInfo>) {
        apps = newApps
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = apps.size

    override fun getItemId(position: Int): Long =
        apps[position].componentName?.hashCode()?.toLong() ?: position.toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val icon = BubbleTextView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        return AppViewHolder(icon)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val app = apps[position]
        holder.icon.applyFromApp(app)
        holder.icon.setOnClickListener { onClick(app) }
        holder.icon.setOnLongClickListener {
            onLongClick(app, holder.icon)
            true
        }
    }
}
