#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <hha_void:void_noise.glsl>

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vColor;
in vec2 vUV;
in vec3 vNormal;
in vec3 vPos;
flat in ivec2 vMode;

out vec4 fragColor;

// Blockschattierung wie bei Vanilla: oben hell, Seiten dunkler
float face_shade(vec3 normal) {
    if (normal.y > 0.5) return 1.0;
    if (normal.y < -0.5) return 0.55;
    return abs(normal.x) > 0.5 ? 0.7 : 0.85;
}

void main() {
    float time = floor(GameTime * 1200.0 * 8.0) / 8.0;
    float shade = face_shade(normalize(vNormal));
    vec3 color;

    if (vMode.x == 2) {
        // Fertig bemaltes Blockmodell: a = Eigenleuchten, leuchtende Teile bekommen keinen Schatten
        color = vColor.rgb * mix(shade, 1.0, vColor.a);
    } else if (vMode.x == 0) {
        // Magmafels: rgb = Objektposition, a = Hitze, gerastert wie eine Blocktextur
        vec3 p = floor((vColor.rgb * 2.0 - 1.0) * 14.0) / 14.0;
        float seed = float(vMode.y) * 3.1;
        float detail = void_noise(p * 7.0 + seed);
        float ridge = abs(void_fbm(p * 3.0 + seed + vec3(0.0, time * 0.1, 0.0)) * 2.0 - 1.0);
        vec3 body = detail > 0.62 ? vec3(0.66, 0.48, 0.66) : (detail > 0.32 ? vec3(0.55, 0.38, 0.56) : vec3(0.42, 0.29, 0.45));
        color = body * shade;
        float heat = vColor.a;
        if (ridge < 0.05 + 0.04 * heat) color = vec3(0.98, 0.84, 1.0);
        else if (ridge < 0.11 + 0.07 * heat) color = vec3(0.82, 0.42, 1.0);
        else if (ridge < 0.16 + 0.07 * heat) color = vec3(0.45, 0.17, 0.85);
    } else {
        // Void-Kristall: rgb = Farbton, a = Gluehen, v = Achse
        float layer = floor(vUV.y * 12.0);
        float wave = step(0.5, fract(layer / 4.0 - time * 1.2));
        vec3 tint = vColor.rgb;
        color = mix(tint * 0.55, tint, wave * vColor.a);
        color = mix(color, vec3(1.0), step(0.86, vUV.y) * 0.8);
        color *= shade;
    }

    fragColor = apply_fog(vec4(color, 1.0), sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
