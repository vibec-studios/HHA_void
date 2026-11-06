#version 330

#moj_import <hha_void:void_noise.glsl>

// Schwarzes Loch als Raymarch ueber das fertig gerenderte Weltbild, im Pixel-Look:
// Strahlen starten aus einem groben Bildraster, die Scheibe rastert in Bloecke und
// Farben kommen aus einer kleinen Palette. Die Lichtablenkung selbst bleibt echt.

uniform sampler2D SceneSampler;
uniform sampler2D DepthSampler;

layout(std140) uniform VoidHole {
    vec4 CenterHorizon;   // xyz = Mitte relativ zur Kamera, w = Horizont-Radius
    vec4 DiskNormalOuter; // xyz = Scheiben-Normale, w = Aussenradius
    vec4 Params;          // x = Zeit, y = Intensitaet, z = Blitz
};

in vec2 texCoord;
flat in mat4 viewProj;
flat in mat4 invViewProj;

out vec4 fragColor;

const int MAX_STEPS = 110;
const vec3 DEEP_SPACE = vec3(0.006, 0.0, 0.016);
const float DISK_CELLS_PER_BLOCK = 2.5;

vec3 sampleScene(vec3 worldPos, vec2 tile, vec2 detail) {
    vec4 clip = viewProj * vec4(worldPos, 1.0);
    if (clip.w <= 0.001) return DEEP_SPACE;
    vec2 uv = clip.xy / clip.w * 0.5 + 0.5;
    // Innerhalb einer Rasterzelle laeuft das Bild weiter: wie gesplittertes Glas, ohne Naht am Rand
    vec2 shift = (uv - tile) * 0.035;
    uv += detail;
    vec3 color = vec3(
        texture(SceneSampler, uv + shift).r,
        texture(SceneSampler, uv).g,
        texture(SceneSampler, uv - shift).b
    );
    vec2 edge = smoothstep(vec2(-0.25), vec2(0.04), uv) * smoothstep(vec2(1.25), vec2(0.96), uv);
    return mix(DEEP_SPACE, color, edge.x * edge.y);
}

vec4 shadeDisk(vec3 hit, vec3 rayDir, vec3 normal, float rs, float outer, float rawTime) {
    vec3 axisU = normalize(cross(normal, abs(normal.y) > 0.9 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0)));
    vec3 axisV = cross(normal, axisU);

    // Scheibe als Raster aus Bloecken
    vec2 flat2 = vec2(dot(hit, axisU), dot(hit, axisV));
    flat2 = (floor(flat2 * DISK_CELLS_PER_BLOCK) + 0.5) / DISK_CELLS_PER_BLOCK;
    float r = length(flat2);
    float inner = rs * 2.3;
    float x = (r - inner) / (outer - inner);
    if (x < 0.0 || x > 1.0) return vec4(0.0);

    float time = floor(rawTime * 10.0) / 10.0;
    float angle = atan(flat2.y, flat2.x);

    // Kepler-Scherung: innen schneller als aussen, zieht die Struktur zu Spiralen
    float spin = angle + time * 4.6 * pow(inner / r, 1.5);
    float logR = log(r / rs);
    vec3 domain = vec3(cos(spin) * 2.4, sin(spin) * 2.4, logR * 3.2);
    float clouds = void_fbm(domain * 1.6);
    float arms = 0.5 + 0.5 * sin(spin * 3.0 + logR * 7.0);
    float density = clouds * 0.82 + arms * 0.3;

    // Beaming: die auf uns zulaufende Seite strahlt heller, in groben Stufen
    vec3 orbit = cross(normal, normalize(axisU * flat2.x + axisV * flat2.y));
    float beta = 0.62 * sqrt(inner / r);
    float doppler = 1.0 / max(0.2, 1.0 + beta * dot(orbit, rayDir));
    float beam = floor(doppler * doppler * 3.0) / 3.0 + 0.2;

    // Feste Palette von aussen nach innen
    float heat = pow(1.0 - x, 1.8);
    vec3 color;
    if (heat > 0.74) color = vec3(0.98, 0.86, 1.0);
    else if (heat > 0.48) color = vec3(0.86, 0.46, 1.0);
    else if (heat > 0.26) color = vec3(0.6, 0.24, 0.96);
    else if (heat > 0.1) color = vec3(0.38, 0.13, 0.76);
    else color = vec3(0.21, 0.07, 0.48);
    if (doppler > 1.5) color = mix(color, vec3(0.55, 0.85, 1.0), 0.6);
    if (doppler < 0.72) color = mix(color, vec3(0.7, 0.05, 0.42), 0.6);

    float alpha = density > 0.52 ? 0.97 : (density > 0.36 ? 0.6 : 0.16);
    if (x > 0.72 && density < 0.45) alpha = 0.0;
    return vec4(color * (0.55 + 1.1 * heat) * beam, alpha);
}

void main() {
    vec3 scene = texture(SceneSampler, texCoord).rgb;
    float intensity = Params.y;
    float flash = Params.z;

    // Das Loch schluckt Licht: die ganze Szene wird dunkler und kippt ins Violette
    vec2 centeredReal = texCoord * 2.0 - 1.0;
    float vignette = smoothstep(0.35, 1.5, length(centeredReal));
    vec3 dimmed = mix(scene, scene * vec3(0.52, 0.42, 0.72), intensity * (0.06 + 0.2 * vignette));
    dimmed += vec3(0.75, 0.5, 1.0) * flash;

    // Strahlen aus einem groben Raster: so wird alles um das Loch herum zu Pixeln
    vec2 size = vec2(textureSize(SceneSampler, 0));
    float tilePixels = max(2.0, floor(size.y / 270.0));
    vec2 tile = (floor(texCoord * size / tilePixels) + 0.5) * tilePixels / size;
    vec2 detail = texCoord - tile;
    vec2 centered = tile * 2.0 - 1.0;

    vec4 farPoint = invViewProj * vec4(centered, 1.0, 1.0);
    vec3 dir = normalize(farPoint.xyz / farPoint.w);

    vec3 center = CenterHorizon.xyz;
    float rs = CenterHorizon.w;
    float outer = DiskNormalOuter.w;
    vec3 normal = DiskNormalOuter.xyz;
    float influence = max(outer * 1.35, rs * 9.0);

    float along = dot(center, dir);
    float camDist2 = dot(center, center);
    float closest2 = camDist2 - along * along;
    bool cameraInside = camDist2 < influence * influence;
    if (rs <= 0.01 || (!cameraInside && (along < 0.0 || closest2 > influence * influence))) {
        fragColor = vec4(dimmed, 1.0);
        return;
    }

    // Tiefe am echten Pixel: Objekte vor dem Loch behalten scharfe Kanten
    float depth = texture(DepthSampler, texCoord).r;
    float sceneDist = 1e6;
    if (depth < 0.99999) {
        vec4 point = invViewProj * vec4(centeredReal, depth * 2.0 - 1.0, 1.0);
        sceneDist = length(point.xyz / point.w);
    }

    float entry = cameraInside ? 0.0 : along - sqrt(max(0.0, influence * influence - closest2));
    if (sceneDist < entry) {
        fragColor = vec4(dimmed, 1.0);
        return;
    }

    vec3 rel = dir * entry - center;
    vec3 vel = dir;
    float travelled = entry;
    vec3 angular = cross(rel, vel);
    float h2 = dot(angular, angular);

    vec3 accum = vec3(0.0);
    float transmit = 1.0;
    float minR = 1e6;
    bool captured = false;
    bool blocked = false;

    for (int i = 0; i < MAX_STEPS; i++) {
        float r2 = dot(rel, rel);
        float r = sqrt(r2);
        minR = min(minR, r);
        if (r < rs) {
            captured = true;
            break;
        }
        if (travelled > sceneDist) {
            blocked = true;
            break;
        }
        if (r > influence && dot(rel, vel) > 0.0) break;

        float dt = clamp(0.2 * (r - rs * 0.7), rs * 0.04, rs * 1.6);
        // Schwarzschild-Naeherung; zum Rand hin ausgeblendet, damit keine Naht entsteht
        float window = 1.0 - smoothstep(influence * 0.5, influence, r);
        vec3 acc = -1.5 * rs * h2 * rel / (r2 * r2 * r) * window;
        vec3 prev = rel;
        vel += acc * dt;
        rel += normalize(vel) * dt;
        travelled += dt;

        float d0 = dot(prev, normal);
        float d1 = dot(rel, normal);
        if (d0 * d1 < 0.0 && transmit > 0.01) {
            vec3 hit = mix(prev, rel, d0 / (d0 - d1));
            vec4 disk = shadeDisk(hit, normalize(vel), normal, rs, outer, Params.x);
            accum += disk.rgb * disk.a * transmit;
            transmit *= 1.0 - disk.a;
        }
    }

    vec3 background = vec3(0.0);
    vec3 emission = accum;
    if (!captured) {
        float remaining = blocked ? 0.0 : clamp(sceneDist - travelled, 2.0, 1000.0);
        background = sampleScene(rel + center + normalize(vel) * remaining, tile, detail);
        // Gleiche Abdunklung wie ausserhalb, damit am Rand keine Naht sichtbar wird
        background = mix(background, background * vec3(0.52, 0.42, 0.72), intensity * (0.06 + 0.2 * vignette));
        float near = smoothstep(influence, rs * 1.5, minR);
        background = mix(background, background * vec3(0.55, 0.42, 0.8), intensity * near);

        // Photonenring und Halo in harten Baendern
        float gap = abs(minR - rs * 1.32) / rs;
        if (gap < 0.13) emission += vec3(1.0, 0.92, 1.0) * 1.3;
        else if (gap < 0.26) emission += vec3(0.8, 0.42, 1.0) * 0.85;
        else if (minR < rs * 2.1) emission += vec3(0.42, 0.13, 0.9) * 0.4;
        else if (minR < rs * 3.0) emission += vec3(0.24, 0.07, 0.55) * 0.3;
    }

    vec3 color = background * transmit + min(emission, vec3(1.0)) * intensity;
    fragColor = vec4(color + vec3(0.75, 0.5, 1.0) * flash, 1.0);
}
