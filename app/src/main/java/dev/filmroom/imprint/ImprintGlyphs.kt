package dev.filmroom.imprint

/** Original procedural lettering, interpreted from the five reference families. No font or photo pixels. */
internal object ImprintGlyphs {
    data class Stroke(val x1: Double, val y1: Double, val x2: Double, val y2: Double, val radius: Double)
    data class Glyph(val advance: Double, val strokes: List<Stroke>)
    private val dots = mapOf(
        '0' to "01110/10001/10001/10001/10001/10001/01110",
        '1' to "00100/01100/00100/00100/00100/00100/01110",
        '2' to "01110/10001/00001/00010/00100/01000/11111",
        '3' to "11110/00001/00001/01110/00001/00001/11110",
        '4' to "00010/00110/01010/10010/11111/00010/00010",
        '5' to "11111/10000/10000/11110/00001/00001/11110",
        '6' to "00110/01000/10000/11110/10001/10001/01110",
        '7' to "11111/00001/00010/00100/00100/00100/00100",
        '8' to "01110/10001/10001/01110/10001/10001/01110",
        '9' to "01110/10001/10001/01111/00001/00010/01100",
        'A' to "01110/10001/10001/11111/10001/10001/10001",
        'B' to "11110/10001/10001/11110/10001/10001/11110",
        'C' to "01111/10000/10000/10000/10000/10000/01111",
        'D' to "11110/10001/10001/10001/10001/10001/11110",
        'E' to "11111/10000/10000/11110/10000/10000/11111",
        'F' to "11111/10000/10000/11110/10000/10000/10000",
        'G' to "01111/10000/10000/10111/10001/10001/01111",
        'H' to "10001/10001/10001/11111/10001/10001/10001",
        'I' to "01110/00100/00100/00100/00100/00100/01110",
        'J' to "00001/00001/00001/00001/10001/10001/01110",
        'K' to "10001/10010/10100/11000/10100/10010/10001",
        'L' to "10000/10000/10000/10000/10000/10000/11111",
        'M' to "10001/11011/10101/10101/10001/10001/10001",
        'N' to "10001/11001/11001/10101/10011/10011/10001",
        'O' to "01110/10001/10001/10001/10001/10001/01110",
        'P' to "11110/10001/10001/11110/10000/10000/10000",
        'Q' to "01110/10001/10001/10001/10101/10010/01101",
        'R' to "11110/10001/10001/11110/10100/10010/10001",
        'S' to "01111/10000/10000/01110/00001/00001/11110",
        'T' to "11111/00100/00100/00100/00100/00100/00100",
        'U' to "10001/10001/10001/10001/10001/10001/01110",
        'V' to "10001/10001/10001/10001/10001/01010/00100",
        'W' to "10001/10001/10001/10101/10101/10101/01010",
        'X' to "10001/10001/01010/00100/01010/10001/10001",
        'Y' to "10001/10001/01010/00100/00100/00100/00100",
        'Z' to "11111/00001/00010/00100/01000/10000/11111",
        '年' to "0010000/0111110/0101000/1111110/0001000/1111111/0001000",
        '月' to "0111110/0100010/0111110/0100010/0111110/0100010/1000110",
        '#' to "11011/10101/01110/11111/01110/10101/11011",
        '日' to "0111110/0100010/0100010/0111110/0100010/0100010/0111110"
    )
    fun glyph(char: Char, style: String): Glyph {
        val c=char.uppercaseChar()
        if(c==' ') return Glyph(.46,emptyList())
        val r=if(style=="canon_data_back") .034 else .044
        fun line(vararg p: Double)=Glyph(.35,listOf(Stroke(p[0],p[1],p[2],p[3],r)))
        when(c) {
            '\'', '’' -> return line(.12,.06,.09,.25)
            '.', ',' -> return line(.10,.95,.10,.95)
            '-' -> return line(.02,.51,.28,.51)
            '+' -> return Glyph(.46,listOf(Stroke(.04,.50,.34,.50,.035),Stroke(.19,.35,.19,.65,.035)))
            '±' -> return Glyph(.45,listOf(Stroke(.04,.42,.34,.42,r),Stroke(.19,.26,.19,.58,r),Stroke(.04,.80,.34,.80,r)))
            '/' -> return Glyph(.46,listOf(Stroke(.04,.96,.36,.04,r)))
            '[' -> return Glyph(.27,listOf(Stroke(.21,.05,.05,.05,r),Stroke(.05,.05,.05,.95,r),Stroke(.05,.95,.21,.95,r)))
            ']' -> return Glyph(.27,listOf(Stroke(.03,.05,.19,.05,r),Stroke(.19,.05,.19,.95,r),Stroke(.19,.95,.03,.95,r)))
            '*' -> return Glyph(.52,listOf(Stroke(.06,.30,.40,.70,r),Stroke(.06,.70,.40,.30,r),Stroke(.23,.22,.23,.78,r)))
            ':' -> return Glyph(.28,listOf(Stroke(.10,.30,.10,.30,r),Stroke(.10,.78,.10,.78,r)))
        }
        if(style=="contax_t2" && c in '0'..'9') {
            val segments=arrayOf("abcdef","bc","abged","abgcd","fgbc","afgcd","afgecd","abc","abcdefg","abfgcd")
            val lines=mapOf('a' to doubleArrayOf(.10,.04,.46,.04),'b' to doubleArrayOf(.51,.10,.51,.44),
                'c' to doubleArrayOf(.51,.57,.51,.91),'d' to doubleArrayOf(.10,.97,.46,.97),
                'e' to doubleArrayOf(.05,.57,.05,.91),'f' to doubleArrayOf(.05,.10,.05,.44),
                'g' to doubleArrayOf(.10,.505,.46,.505))
            return Glyph(.72,segments[c-'0'].map { val p=lines.getValue(it); Stroke(p[0],p[1],p[2],p[3],.041) })
        }
        if(style=="canon_data_back" && c in '0'..'9') {
            // Slender continuous paths, deliberately not joined seven-segment glyphs.
            val paths=arrayOf(
                ".28,0 .10,.07 .04,.24 .04,.76 .10,.94 .28,1 .45,.94 .51,.76 .51,.24 .45,.07 .28,0",
                ".08,.19 .28,0 .28,1", ".03,.19 .11,.04 .30,0 .46,.07 .51,.23 .46,.39 .03,.96 .52,.96",
                ".04,.06 .47,.06 .28,.47 .44,.50 .51,.66 .48,.87 .31,1 .10,.96 .03,.86",
                ".42,1 .42,0 .01,.70 .55,.70", ".50,.03 .08,.03 .05,.46 .29,.43 .46,.50 .52,.70 .47,.90 .30,1 .10,.96 .03,.85",
                ".48,.06 .30,0 .12,.13 .04,.42 .04,.78 .13,.96 .31,1 .48,.88 .52,.67 .44,.49 .27,.44 .07,.53",
                ".03,.03 .53,.03 .30,.48 .18,1", ".28,0 .10,.05 .04,.23 .10,.40 .42,.60 .51,.77 .44,.95 .28,1 .10,.95 .03,.78 .11,.60 .43,.40 .51,.23 .44,.05 .28,0",
                ".48,.49 .29,.56 .11,.50 .04,.32 .08,.12 .25,0 .43,.05 .51,.23 .51,.59 .44,.85 .27,1 .08,.94")
            var points=paths[c-'0'].split(' ').map { it.split(',').map(String::toDouble) }
            if(c !in "147") repeat(2) {
                // Corner-cut our own control polygon into a soft optical stroke, never a font outline.
                val smooth=mutableListOf(points.first())
                points.zipWithNext().forEach { (a,b) ->
                    smooth+=listOf(a[0]*.75+b[0]*.25,a[1]*.75+b[1]*.25)
                    smooth+=listOf(a[0]*.25+b[0]*.75,a[1]*.25+b[1]*.75)
                }
                smooth+=points.last();points=smooth
            }
            return Glyph(.72,points.zipWithNext().map{(a,b)->Stroke(a[0],a[1],b[0],b[1],.029)})
        }
        val pattern=dots[c] ?: throw IllegalArgumentException("Unsupported imprint character: $char")
        val rows=pattern.split('/'); val cols=rows[0].length
        val stepX=if(style=="pentax_mzs") .115 else .137
        val radius=when(style){"canon_luna"->.049;"pentax_mzs"->.032;else->.043}
        val strokes=mutableListOf<Stroke>()
        rows.forEachIndexed { y,row -> row.forEachIndexed { x,bit -> if(bit=='1') {val xx=x*stepX;val yy=y/6.0;strokes+=Stroke(xx,yy,xx,yy,radius)} } }
        return Glyph(cols*stepX+.13,strokes)
    }
}
