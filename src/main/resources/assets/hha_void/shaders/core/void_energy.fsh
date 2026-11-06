#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <hha_void:void_noise.glsl>

// Energie im Minecraft-Look: alles rastert auf grobe Pixel, Helligkeit und Zeit laufen
// in Stufen statt fliessend.

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vColor;
in vec2 vUV;
in vec3 vNormal;
in vec3 vPos;
flat in ivec2 vMode;

out vec4 fragColor;

const int MODE_RIBBON = 0;
const int MODE_SHELL = 1;
const int MODE_GLOW = 2;
const int MODE_SWIRL = 3;
const int MODE_SHOCK = 4;
const int MODE_FLAME = 5;
const int MODE_RUNE = 6;
const int MODE_STAIN = 7;
const int MODE_SKY_RIFT = 8;
const int MODE_SKY_DOME = 9;

const float TAU = 6.2831853;

// Fester Punkt in der Welt, gerastert: Texel bleiben stehen, wenn sich die Kamera bewegt
vec3 world_cell(float perBlock) {
    vec3 world = vPos - CameraOffset + mod(vec3(CameraBlockPos), 1024.0);
    return floor(world * perBlock) / perBlock;
}

// Blockschattierung wie bei Vanilla: oben hell, Seiten dunkler
float face_shade(vec3 normal) {
    if (normal.y > 0.5) return 1.0;
    if (normal.y < -0.5) return 0.55;
    return abs(normal.x) > 0.5 ? 0.7 : 0.85;
}

float steps(float value, float count) {
    return ceil(clamp(value, 0.0, 1.0) * count) / count;
}

void main() {
    float time = floor(GameTime * 1200.0 * 12.0) / 12.0;
    float seed = float(vMode.y & 255) * 0.37;
    float cells = max(float((vMode.y >> 8) & 127), 3.0);
    vec2 snapped = (floor(vUV * cells) + 0.5) / cells;
    float radius = length(snapped);
    vec3 rgb = vColor.rgb;
    float a = steps(vColor.a, 6.0);

    if (vMode.x == MODE_RIBBON) {
        rgb *= face_shade(vNormal);
        vec3 cell = world_cell(4.0);
        a *= 0.7 + 0.3 * void_noise(cell * 2.0 + vec3(time * 3.0, 0.0, seed));
    } else if (vMode.x == MODE_SHELL) {
        vec3 cell = world_cell(4.0);
        float n = void_fbm(cell * 0.8 + vec3(0.0, -time * 1.4, seed));
        float level = floor(n * 6.0) / 3.0;
        if (level < 0.34) discard;
        a *= clamp(level, 0.0, 1.0) * 0.72;
        rgb = mix(rgb, vec3(1.0), step(1.0, level) * 0.22) * face_shade(vNormal);
    } else if (vMode.x == MODE_GLOW) {
        float diamond = abs(snapped.x) + abs(snapped.y);
        float arm = min(abs(snapped.x), abs(snapped.y));
        float level = 0.0;
        if (diamond < 0.22) level = 0.95;
        else if (diamond < 0.45) level = 0.6;
        else if (diamond < 0.72) level = 0.28;
        else if (arm < 1.0 / cells && diamond < 1.0) level = 0.42;
        if (level <= 0.0) discard;
        a *= level;
        rgb = mix(rgb, vec3(1.0), step(0.9, level) * 0.55);
    } else if (vMode.x == MODE_SWIRL) {
        if (radius > 1.0) discard;
        float th = atan(snapped.y, snapped.x) + radius * 5.0 - time * 3.2;
        float swirl = void_fbm(vec3(cos(th) * 2.0, sin(th) * 2.0, radius * 2.5 + seed));
        float band = floor(swirl * 5.0) / 4.0;
        rgb = radius > 0.82 ? mix(rgb, vec3(1.0), 0.3) : rgb * band * band * smoothstep(0.1, 0.8, radius);
    } else if (vMode.x == MODE_SHOCK) {
        float inner = vNormal.x;
        if (radius > 1.0 || radius < inner) discard;
        float v = (radius - inner) / max(1.0 - inner, 0.001);
        vec3 grid = vec3(snapped * cells, seed);
        float n = void_noise(grid * 0.7 + vec3(0.0, 0.0, time * 2.0));
        float level = steps(v * v * (0.55 + 0.9 * n), 4.0);
        if (level < 0.5 && n < 0.4) discard;
        a *= level;
        rgb = mix(rgb, vec3(1.0), step(0.88, v) * 0.45);
    } else if (vMode.x == MODE_FLAME) {
        vec3 cell = world_cell(3.0);
        float n = void_fbm(cell * 0.9 + vec3(0.0, -time * 3.6, seed));
        float v = vUV.y;
        if (n * 1.25 + 0.12 < v) discard;
        a *= steps(1.0 - v * 0.7, 4.0);
        rgb = mix(rgb, vec3(1.0), step(v, 0.22) * 0.5) * face_shade(vNormal);
    } else if (vMode.x == MODE_RUNE) {
        float inner = vNormal.x;
        if (radius > 1.0 || radius < inner) discard;
        float slot = floor(fract(atan(snapped.y, snapped.x) / TAU + vNormal.y) * 36.0);
        if (mod(slot, 4.0) > 2.5) discard;
        a *= mod(slot, 8.0) < 1.0 ? 1.0 : 0.7;
        rgb = mix(rgb, vec3(1.0), step(mod(slot, 8.0), 0.5) * 0.5);
    } else if (vMode.x == MODE_STAIN) {
        if (radius > 1.0) discard;
        float th = atan(snapped.y, snapped.x) * 3.0 + log(max(radius, 0.02)) * 7.0 - time * 1.6;
        float groove = step(0.0, sin(th));
        float n = void_noise(vec3(snapped * cells * 0.7, seed));
        a *= steps(1.0 - radius * radius, 4.0) * (0.72 + 0.28 * groove);
        if (n > 0.8 - (1.0 - radius) * 0.6 && radius > 0.45) discard;
        rgb = rgb * (0.5 * groove * step(0.4, n) + 0.08);
    }

    if (vMode.x == MODE_SKY_DOME) {
        // Zum Horizont hin dichter, mit grob gerasterten Wolkenfeldern
        vec3 dir = normalize(vPos);
        vec3 block = floor(dir * 26.0);
        float cloud = void_noise(block * 0.45 + vec3(time * 0.18, 0.0, 0.0));
        a *= 0.55 + 0.5 * vUV.y + floor(cloud * 4.0) * 0.1;
        rgb *= 0.75 + floor(cloud * 4.0) * 0.14;
    }

    float fog = (vMode.x == MODE_SKY_RIFT || vMode.x == MODE_SKY_DOME) ? 0.0
        : total_fog_value(sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd);
    a = clamp(a, 0.0, 1.0) * (1.0 - fog);
    if (a <= 0.002) discard;
    fragColor = vec4(rgb, a);
}
