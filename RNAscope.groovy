// ======================================================
// RNAscope PRO: PUREZA TOTAL + ESTADÍSTICAS GLOBALES
// Versión: 1.2 (QuPath 0.7.0)
// ======================================================

import qupath.lib.regions.RegionRequest
import qupath.lib.roi.RoiTools
import java.awt.Color
import java.awt.image.BufferedImage

println "=== Iniciando Análisis: RNAscope con Estadísticas de Porcentaje ==="

def imageData = getCurrentImageData()
def server = imageData.getServer()
def selectedAnnotations = getSelectedObjects().findAll { it.isAnnotation() }

if (selectedAnnotations.isEmpty()) {
    println "ERROR: Selecciona una anotación primero."
    return
}

// --- PARÁMETROS CROMÁTICOS (REGLAS DE PUREZA) ---
double MIN_RED_VS_BLUE = 22      
double MIN_RED_VS_GREEN = 55     
double YELLOW_LIMIT_RATIO = 0.55 
double AVG_AREA_PUNCTA = 5.0 

setImageType('BRIGHTFIELD_H_DAB')
removeDetections()

// 1. DETECCIÓN DE CÉLULAS
def cellArgs = """{"detectionImageBrightfield": "Hematoxylin OD", "sigmaMicrons": 1.2, "minAreaMicrons": 3.0, "cellExpansionMicrons": 5.0, "includeNuclei": true}"""
runPlugin('qupath.imagej.detect.cells.WatershedCellDetection', cellArgs)

selectedAnnotations.each { annotation ->
    def cells = annotation.getChildObjects().findAll { it.isCell() }
    
    // Contadores para estadísticas
    double nNeg = 0, n1 = 0, n2 = 0, n3 = 0
    double totalCells = cells.size()

    // 2. PROCESAMIENTO DE CÉLULAS Y CLASIFICACIÓN
    cells.each { cell ->
        def roi = cell.getROI()
        if (roi == null) return
        def request = RegionRequest.createInstance(server.getPath(), 1.0, roi)
        BufferedImage img = server.readRegion(request)
        if (img == null) return

        def shape = RoiTools.getShape(roi)
        int w = img.getWidth(), h = img.getHeight()
        int x0 = request.getX(), y0 = request.getY()
        byte[] redMask = new byte[w * h]
        int countValidPixels = 0

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!shape.contains(x0 + x + 0.5, y0 + y + 0.5)) continue
                
                int rgb = img.getRGB(x, y)
                int r = (rgb >> 16) & 0xff
                int g = (rgb >> 8) & 0xff
                int b = rgb & 0xff
                float brightness = Color.RGBtoHSB(r, g, b, null)[2]

                // Filtros de pureza (del paso anterior)
                if (b >= r || (r > 240 && g > 240 && b > 240)) continue
                if (g > r * YELLOW_LIMIT_RATIO || r < g + MIN_RED_VS_GREEN) continue
                if (g > b + 5) continue 

                boolean isRNAscopeRed = false
                if (r > g + MIN_RED_VS_GREEN && r > b + MIN_RED_VS_BLUE) {
                    isRNAscopeRed = true
                } else if (brightness < 0.45 && r > b * 1.3 && r > g + 25) {
                    isRNAscopeRed = true
                }

                if (isRNAscopeRed) {
                    redMask[y * w + x] = 1
                    countValidPixels++
                }
            }
        }

        int punctaCount = countValidPixels >= 2 ? calculatePuncta(redMask, w, h, 2, AVG_AREA_PUNCTA) : 0
        
        int score = 0
        if (punctaCount >= 1) score = 1
        if (punctaCount >= 4) score = 2
        if (punctaCount >= 10) score = 3
        
        // Asignar clase y aumentar contador
        String className = "Negative"
        if (score == 1) { className = "RNAscope 1+"; n1++ }
        else if (score == 2) { className = "RNAscope 2+"; n2++ }
        else if (score == 3) { className = "RNAscope 3+"; n3++ }
        else { nNeg++ }

        cell.getMeasurementList().put("Puncta_Count", (double)punctaCount)
        cell.setPathClass(getPathClass(className))
    }

    // 3. CÁLCULO DE PORCENTAJES GLOBALES
    if (totalCells > 0) {
        double pNeg = (nNeg / totalCells) * 100
        double p1 = (n1 / totalCells) * 100
        double p2 = (n2 / totalCells) * 100
        double p3 = (n3 / totalCells) * 100
        double pPosTotal = ((n1 + n2 + n3) / totalCells) * 100

        // Guardar resultados en la Anotación
        def measureList = annotation.getMeasurementList()
        measureList.put("Percent Negative", pNeg)
        measureList.put("Percent RNAscope 1+", p1)
        measureList.put("Percent RNAscope 2+", p2)
        measureList.put("Percent RNAscope 3+", p3)
        measureList.put("Percent Positive Total", pPosTotal)
        measureList.put("Total Cells Counted", totalCells)
    }
}

// Función auxiliar de conteo
int calculatePuncta(byte[] mask, int w, int h, int minArea, double avgArea) {
    boolean[] visited = new boolean[w * h]
    int totalPuncta = 0
    for (int i = 0; i < mask.length; i++) {
        if (mask[i] == 0 || visited[i]) continue
        int area = 0
        def stack = [i]
        visited[i] = true
        while (stack) {
            int curr = stack.pop(); area++
            int cx = curr % w, cy = (int)(curr / w)
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = cx + dx, ny = cy + dy
                    if (nx >= 0 && nx < w && ny >= 0 && ny < h) {
                        int ni = ny * w + nx
                        if (mask[ni] == 1 && !visited[ni]) {
                            visited[ni] = true; stack.push(ni)
                        }
                    }
                }
            }
        }
        if (area >= minArea) totalPuncta += (int)Math.max(1, Math.round(area / avgArea))
    }
    return totalPuncta
}

println "=== ANÁLISIS COMPLETADO: Porcentajes generados en la tabla de medidas ==="