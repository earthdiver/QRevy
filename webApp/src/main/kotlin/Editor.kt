package qrevy.web

import io.github.earthdiver.qrrepair.*
import io.github.earthdiver.qrrepair.core.*
import org.teavm.jso.JSObject

internal object Editor {
    fun validate(m: QrMatrix, s: PreprocessSettings) {
        val priority=m.prioritySearchCoordinates().map { Coord(it.first,it.second) }
        require(priority.size<=s.prioritySearchLimit) { localizedText("*の指定数が上限${s.prioritySearchLimit}を超えています。指定は保持しています。", "Priority marks exceed the limit of ${s.prioritySearchLimit}. Existing marks are preserved.") }
        val allowed=QrCellRegions(s.version).codewordCoordinates
        require(priority.all { it in allowed }) { "Priority search requires data/ECC cells, excluding remainder bits" }
        FormatEditPolicy.conflict(s.version,s.formatConstraint,FormatEditPolicy.manualValues(m))?.let { error(it.message()) }
    }
    fun run(o: JSObject): Any {
        val m=readMatrix(o.obj("matrix"));val s=settings(o.obj("settings"));val regions=QrCellRegions(s.version)
        if(o.str("action")=="regions") return mapOf("allowed" to regions.codewordCoordinates.map { it.y*m.dimension+it.x },"kinds" to (0 until m.dimension*m.dimension).map{index->
            val coord=Coord(index%m.dimension,index/m.dimension)
            "cell_region_"+if(coord in regions.codewordCoordinates)"codeword" else when(regions.structureAt(coord)){
                RepairReasonKind.FINDER_PATTERN->"finder";RepairReasonKind.SEPARATOR->"separator";RepairReasonKind.TIMING_PATTERN->"timing";RepairReasonKind.ALIGNMENT_PATTERN->"alignment";RepairReasonKind.FORMAT_INFORMATION->"format";RepairReasonKind.VERSION_INFORMATION->"version";RepairReasonKind.FIXED_DARK_MODULE->"dark";null->"remainder";else->"structure"
            }
        },"limits" to mapOf("format" to ErrorLimitMode.entries.associate{it.name to ErrorLimitPolicy.format(it,s.formatMismatchLimit)},"data" to ErrorLimitMode.entries.associate{it.name to ErrorLimitPolicy.data(s.version,it,s.dataEccMismatchLimit)}))
        if(o.str("action")=="validate") { FormatEditPolicy.conflict(s.version,s.formatConstraint,FormatEditPolicy.manualValues(m))?.let { error(it.message()) };return true }
        val source=readMatrix(o.obj("source"));require(source.dimension==m.dimension)
        val edits=mutableMapOf<Coord,ModuleValue>();val soft=mutableSetOf<Coord>()
        for(y in 0 until m.dimension)for(x in 0 until m.dimension)if(m[x,y].source.isUserEdit || m[x,y].prioritySearch){val c=Coord(x,y);edits[c]=m[x,y].value;if(m[x,y].source==ModuleSource.USER_SOFT)soft+=c}
        val state=PreRecoveryCellEditState(edits,m.prioritySearchCoordinates().map { Coord(it.first,it.second) }.toSet(),s.prioritySearchLimit,{ values ->
            FormatEditPolicy.conflict(s.version,s.formatConstraint,values)?.let { error(it.message()) };true
        },soft)
        val a=o.obj("cells");require(Interop.length(a)<=m.dimension*m.dimension)
        val coords=(0 until Interop.length(a)).map { val i=Interop.item(a,it).toInt();require(i in 0 until m.dimension*m.dimension);Coord(i%m.dimension,i/m.dimension) }.distinct()
        val value=o.str("value")
        when(value) {
            "*" -> {
                require(s.logicalGridAssigned) { localizedText("*を指定するには、先に四隅の位置と役割を指定してください。", "Assign corner positions and roles before marking priority cells.") }
                require(coords.all { it in regions.codewordCoordinates }) { localizedText("*はデータ・ECCセルにのみ指定できます。余りビットや機能パターンは指定できません。", "Priority cells must be data/ECC cells, excluding remainder bits and function patterns.") }
                require(state.setPrioritySearch(coords)!=PriorityEditResult.LIMIT_EXCEEDED) { localizedText("*は最大${s.prioritySearchLimit}セルです。詳細設定で変更できます。", "Up to ${s.prioritySearchLimit} priority cells. Change the limit in settings.") }
            }
            "source" -> state.setSource(coords,when(o.str("kind")){"0"->CellEditSource.IMAGE;"5"->CellEditSource.TENTATIVE;else->CellEditSource.FIXED}) { m[it.x,it.y].value }
            else -> state.applyValues(coords,when(value){"0"->ModuleValue.ZERO;"1"->ModuleValue.ONE;else->ModuleValue.ERASED}) { m[it.x,it.y].value }
        }
        require(state.lastEditOutcome!=CellEditOutcome.REJECTED) { localizedText("不明セルは固定できません。変更は適用されません。", "Unknown cells cannot be fixed. No changes applied.") }
        // Preserve recovered cells outside the selection. Only an explicit source
        // restore should replace working values with the original observations.
        for(c in coords) {
            m.setPrioritySearch(c.x,c.y,false)
            if(value=="source" && o.str("kind")=="0") {
                val original=source[c.x,c.y]
                m.set(c.x,c.y,original.value,original.confidence,original.source)
                val margin=source.imageMargin(c.x,c.y)
                if(margin>=0)m.setImageMargin(c.x,c.y,margin)
            }
        }
        state.applyTo(m,false)
        return matrixJson(m)
    }
}
