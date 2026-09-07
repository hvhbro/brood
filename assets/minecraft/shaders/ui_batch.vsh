#version 130

uniform mat4 projectionMatrix;

const float UvScale = 4.0;
const float UvBias = -1.0;
const float OutlineMaxPx = 8.0;

in vec3 position;
in vec4 color;
in vec2 texCoords;
in vec4 params;

out vec4 passColor;
out vec2 passTexCoords;
flat out int passSlot;
flat out int passKind;
flat out float passOutlinePx;
flat out vec3 passOutlineColor;

void main() {
    gl_Position = projectionMatrix * vec4(position, 1.0);
    passColor = color;
    passTexCoords = texCoords * UvScale + UvBias;

    int material = int(params.x + 0.5);
    passSlot = material & 7;
    passKind = (material >> 3) & 7;

    passOutlinePx = params.y / 255.0 * OutlineMaxPx;

    int outlineBits = int(params.z + 0.5) | (int(params.w + 0.5) << 8);
    passOutlineColor = vec3(
        float((outlineBits >> 11) & 31) / 31.0,
        float((outlineBits >> 5) & 63) / 63.0,
        float(outlineBits & 31) / 31.0
    );
}
