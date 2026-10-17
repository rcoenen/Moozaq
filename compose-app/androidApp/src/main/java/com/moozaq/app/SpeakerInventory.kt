package com.moozaq.app

import com.moozaq.Device

/** Discovery gaps change availability, not row identity or the size of the list. */
internal class SpeakerInventory {
    private val known=linkedMapOf<String,Device>()
    fun remove(id:String) {known.remove(id)}
    fun update(current:List<Device>):List<Device> {
        current.filter {it.temporary}.forEach {known.remove(it.id)}
        current.filterNot {it.temporary}.forEach {known[it.id]=it}
        val present=current.associateBy {it.id}
        return known.values.map {present[it.id] ?: it.copy(available=false)}
            .sortedWith(compareBy<Device> {it.name.lowercase()}.thenBy {it.id}) + current.filter {it.temporary}
    }
}
