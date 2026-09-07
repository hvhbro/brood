#version 130

uniform sampler2D uTextures[8];
uniform float uPxRange;

const int KindTextured = 0;
const int KindMsdf = 1;
const int KindCircle = 2;
const int KindArc = 3;
const int KindTriangle = 4;

in vec4 passColor;
in vec2 passTexCoords;
flat in int passSlot;
flat in int passKind;
flat in float passOutlinePx;
flat in vec3 passOutlineColor;

out vec4 fragColor;

float median(float a, float b, float c) {
    return max(min(a, b), min(max(a, b), c));
}

float sdDownTriangle(vec2 p) {
    vec2 p0 = vec2(0.0, 0.0);
    vec2 p1 = vec2(1.0, 0.0);
    vec2 p2 = vec2(0.5, 1.0);
    vec2 e0 = p1 - p0, e1 = p2 - p1, e2 = p0 - p2;
    vec2 v0 = p - p0, v1 = p - p1, v2 = p - p2;
    vec2 pq0 = v0 - e0 * clamp(dot(v0, e0) / dot(e0, e0), 0.0, 1.0);
    vec2 pq1 = v1 - e1 * clamp(dot(v1, e1) / dot(e1, e1), 0.0, 1.0);
    vec2 pq2 = v2 - e2 * clamp(dot(v2, e2) / dot(e2, e2), 0.0, 1.0);
    float s = sign(e0.x * e2.y - e0.y * e2.x);
    vec2 d = min(min(vec2(dot(pq0, pq0), s * (v0.x * e0.y - v0.y * e0.x)),
    vec2(dot(pq1, pq1), s * (v1.x * e1.y - v1.y * e1.x))),
    vec2(dot(pq2, pq2), s * (v2.x * e2.y - v2.y * e2.x)));
    return -sqrt(d.x) * sign(d.y);
}

vec4 sampleSlot(int slot, vec2 uv) {
    if (slot == 0) return texture(uTextures[0], uv);
    if (slot == 1) return texture(uTextures[1], uv);
    if (slot == 2) return texture(uTextures[2], uv);
    if (slot == 3) return texture(uTextures[3], uv);
    if (slot == 4) return texture(uTextures[4], uv);
    if (slot == 5) return texture(uTextures[5], uv);
    if (slot == 6) return texture(uTextures[6], uv);
    return texture(uTextures[7], uv);
}

vec2 slotSize(int slot) {
    if (slot == 0) return vec2(textureSize(uTextures[0], 0));
    if (slot == 1) return vec2(textureSize(uTextures[1], 0));
    if (slot == 2) return vec2(textureSize(uTextures[2], 0));
    if (slot == 3) return vec2(textureSize(uTextures[3], 0));
    if (slot == 4) return vec2(textureSize(uTextures[4], 0));
    if (slot == 5) return vec2(textureSize(uTextures[5], 0));
    if (slot == 6) return vec2(textureSize(uTextures[6], 0));
    return vec2(textureSize(uTextures[7], 0));
}

void main() {
    if (passKind == KindTriangle) {
        float dist = sdDownTriangle(passTexCoords);
        float aa = fwidth(dist);
        float coverage = clamp(0.5 - dist / aa, 0.0, 1.0);
        fragColor = vec4(passColor.rgb, passColor.a * coverage);
        return;
    }
    if (passKind == KindArc) {
        float rf = passTexCoords.x;
        float aa = fwidth(rf);
        float coverage = clamp(rf / aa + 0.5, 0.0, 1.0) * clamp((1.0 - rf) / aa + 0.5, 0.0, 1.0);
        fragColor = vec4(passColor.rgb, passColor.a * coverage);
        return;
    }
    if (passKind == KindCircle) {
        float dist = length(passTexCoords);
        float aa = fwidth(dist);
        float coverage = clamp((1.0 - dist) / aa + 0.5, 0.0, 1.0);
        fragColor = vec4(passColor.rgb, passColor.a * coverage);
        return;
    }

    if (passKind == KindMsdf) {
        vec3 msd = sampleSlot(passSlot, passTexCoords).rgb;
        float sd = median(msd.r, msd.g, msd.b);
        vec2 unitRange = vec2(uPxRange) / slotSize(passSlot);
        vec2 screenTexSize = vec2(1.0) / fwidth(passTexCoords);
        float screenPxRange = max(0.5 * dot(unitRange, screenTexSize), 1.0);
        float fill = clamp((sd - 0.5) * screenPxRange + 0.5, 0.0, 1.0);
        if (passOutlinePx > 0.0) {
            float outlineW = min(passOutlinePx, max(0.5 * screenPxRange - 0.5, 0.0));
            float outer = clamp((sd - 0.5) * screenPxRange + 0.5 + outlineW, 0.0, 1.0);
            vec3 rgb = mix(passOutlineColor, passColor.rgb, fill);
            fragColor = vec4(rgb, outer * passColor.a);
        } else {
            fragColor = vec4(passColor.rgb, passColor.a * fill);
        }
        return;
    }

    fragColor = sampleSlot(passSlot, passTexCoords) * passColor;
}
