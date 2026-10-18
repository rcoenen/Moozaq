package com.moozaq

/** Member volumes (0..1) are the truth. A group volume is their average, and a group change
 *  scales every member proportionally so the requested average is reached without flattening
 *  the balance set by the individual sliders. */
fun scaledGroupVolumes(levels:List<Double>,target:Double):List<Double> {
    require(levels.isNotEmpty()) {"No speakers."}
    val desired=target.coerceIn(0.0,1.0)
    val average=levels.average()
    val scaled=if(average<=0.0) levels.map {desired} else levels.map {it*desired/average}
    return scaled.map {roundToPercent(it)}
}

/** Null until every current member has a fresh connected volume. A partial average would be a lie. */
fun groupAverageVolume(members:List<GroupMember>):Double? {
    if(members.size<2) return null
    val levels=members.map {
        if(!it.connected) return null
        it.volume ?: return null
    }
    return levels.average()
}

private fun roundToPercent(value:Double)=kotlin.math.round(value.coerceIn(0.0,1.0)*100)/100.0
