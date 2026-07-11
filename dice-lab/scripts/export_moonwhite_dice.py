import math
import os
import sys

import bpy


EXPORTS = {
    "D4": "D4_四面骰_月白冰晶_baked.glb",
    "D6": "D6_六面骰_月白冰晶_baked.glb",
    "D8": "D8_八面骰_月白冰晶_baked.glb",
    "D10_0_9": "D10_个位骰_0-9_月白冰晶_baked.glb",
    "D10_00_90": "D10_百分骰_00-90_月白冰晶_baked.glb",
    "D12": "D12_十二面骰_月白冰晶_baked.glb",
    "D20": "D20_二十面骰_月白冰晶_baked.glb",
}


def output_directory():
    separator = sys.argv.index("--")
    arguments = sys.argv[separator + 1:]
    if len(arguments) != 1:
        raise RuntimeError("Expected output directory after --")
    return os.path.abspath(arguments[0])


def select_only(obj):
    bpy.ops.object.select_all(action="DESELECT")
    obj.hide_set(False)
    obj.hide_viewport = False
    obj.select_set(True)
    bpy.context.view_layer.objects.active = obj


def ensure_uv_map(obj):
    if obj.data.uv_layers:
        return
    select_only(obj)
    bpy.ops.object.mode_set(mode="EDIT")
    bpy.ops.mesh.select_all(action="SELECT")
    bpy.ops.uv.smart_project(angle_limit=1.15192, island_margin=0.03)
    bpy.ops.object.mode_set(mode="OBJECT")


def set_input(node, names, value):
    if isinstance(names, str):
        names = (names,)
    for name in names:
        socket = node.inputs.get(name)
        if socket is not None:
            socket.default_value = value
            return socket
    return None


def shell_material(name):
    material = bpy.data.materials.new(name)
    material.use_nodes = True
    nodes = material.node_tree.nodes
    links = material.node_tree.links
    nodes.clear()
    output = nodes.new("ShaderNodeOutputMaterial")
    principled = nodes.new("ShaderNodeBsdfPrincipled")
    coordinate = nodes.new("ShaderNodeTexCoord")
    noise = nodes.new("ShaderNodeTexNoise")
    noise.noise_dimensions = "4D"
    noise.inputs["Scale"].default_value = 2.7
    noise.inputs["Detail"].default_value = 4.4
    noise.inputs["Roughness"].default_value = 0.58
    noise.inputs["Distortion"].default_value = 0.46
    noise.inputs["W"].default_value = 0.37
    ramp = nodes.new("ShaderNodeValToRGB")
    ramp.color_ramp.elements.remove(ramp.color_ramp.elements[1])
    stops = (
        (0.00, (0.20, 0.48, 0.74, 1.0)),
        (0.28, (0.38, 0.68, 0.91, 1.0)),
        (0.56, (0.67, 0.86, 0.98, 1.0)),
        (0.78, (0.88, 0.96, 1.00, 1.0)),
        (1.00, (0.99, 0.99, 0.97, 1.0)),
    )
    ramp.color_ramp.elements[0].position, ramp.color_ramp.elements[0].color = stops[0]
    for position, color in stops[1:]:
        element = ramp.color_ramp.elements.new(position)
        element.color = color
    links.new(coordinate.outputs["Generated"], noise.inputs["Vector"])
    links.new(noise.outputs["Fac"], ramp.inputs["Fac"])
    links.new(ramp.outputs["Color"], principled.inputs["Base Color"])
    set_input(principled, "Metallic", 0.025)
    set_input(principled, "Roughness", 0.14)
    set_input(principled, ("Transmission Weight", "Transmission"), 0.38)
    set_input(principled, "IOR", 1.455)
    set_input(principled, "Alpha", 0.88)
    set_input(principled, ("Emission Color", "Emission"), (0.06, 0.18, 0.36, 1.0))
    set_input(principled, "Emission Strength", 0.065)
    set_input(principled, ("Coat Weight", "Clearcoat"), 0.34)
    set_input(principled, ("Coat Roughness", "Clearcoat Roughness"), 0.075)
    links.new(principled.outputs["BSDF"], output.inputs["Surface"])
    material.surface_render_method = "BLENDED"
    return material


def bake_body(body, material):
    body.data.materials.clear()
    body.data.materials.append(material)
    ensure_uv_map(body)
    nodes = material.node_tree.nodes
    links = material.node_tree.links
    principled = next(node for node in nodes if node.type == "BSDF_PRINCIPLED")
    image = bpy.data.images.new(
        name=f"{body.name}_MoonwhiteBaseColor",
        width=1024,
        height=1024,
        alpha=False,
    )
    image.colorspace_settings.name = "sRGB"
    texture = nodes.new("ShaderNodeTexImage")
    texture.name = "Baked Moonwhite Base Color"
    texture.image = image
    nodes.active = texture
    texture.select = True
    select_only(body)
    bpy.ops.object.bake(type="DIFFUSE", pass_filter={"COLOR"}, margin=16)
    for link in list(principled.inputs["Base Color"].links):
        links.remove(link)
    links.new(texture.outputs["Color"], principled.inputs["Base Color"])
    image.pack()
    print(f"BAKED {body.name} 1024x1024", flush=True)


def smoothstep(value):
    value = max(0.0, min(1.0, value))
    return value * value * (3.0 - 2.0 * value)


def frost_gradient_image():
    image = bpy.data.images.get("Moonwhite_Web_Frost_Gradient")
    if image is not None:
        return image
    size = 256
    image = bpy.data.images.new("Moonwhite_Web_Frost_Gradient", width=size, height=size, alpha=True)
    pixels = []
    for y in range(size):
        py = (y + 0.5) / size * 2.0 - 1.0
        for x in range(size):
            px = (x + 0.5) / size * 2.0 - 1.0
            radius = math.sqrt(px * px + py * py)
            disturbance = (
                math.sin(px * 11.0 + py * 7.0) * 0.045
                + math.sin(px * 23.0 - py * 17.0) * 0.025
                + math.cos(px * 5.0 + py * 19.0) * 0.018
            )
            strength = smoothstep((1.02 - radius + disturbance) / 0.92)
            red = 0.42 + strength * 0.53
            green = 0.72 + strength * 0.27
            blue = 0.96 + strength * 0.04
            alpha = 0.012 + strength * 0.29
            pixels.extend((red, green, blue, alpha))
    image.pixels.foreach_set(pixels)
    image.pack()
    return image


def replace_gradient_materials():
    image = frost_gradient_image()
    material = bpy.data.materials.new("Moonwhite_Web_Continuous_Frost")
    material.use_nodes = True
    nodes = material.node_tree.nodes
    links = material.node_tree.links
    nodes.clear()
    output = nodes.new("ShaderNodeOutputMaterial")
    principled = nodes.new("ShaderNodeBsdfPrincipled")
    texture = nodes.new("ShaderNodeTexImage")
    texture.image = image
    texture.interpolation = "Linear"
    links.new(texture.outputs["Color"], principled.inputs["Base Color"])
    links.new(texture.outputs["Alpha"], principled.inputs["Alpha"])
    set_input(principled, "Metallic", 0.02)
    set_input(principled, "Roughness", 0.47)
    set_input(principled, ("Emission Color", "Emission"), (0.12, 0.32, 0.52, 1.0))
    set_input(principled, "Emission Strength", 0.04)
    links.new(principled.outputs["BSDF"], output.inputs["Surface"])
    material.surface_render_method = "BLENDED"
    for obj in bpy.context.scene.objects:
        if "Continuous_Frost_Gradient_v3" not in obj.name:
            continue
        obj.data.materials.clear()
        obj.data.materials.append(material)


def convert_export_curves_and_fonts():
    for obj in list(bpy.context.scene.objects):
        is_number = obj.type == "FONT"
        is_export_curve = obj.type == "CURVE" and any(token in obj.name for token in (
            "Moonwhite_Broken_Ice_Seam",
            "Every_Face_Vertex_Frost_v6",
        ))
        if not is_number and not is_export_curve:
            continue
        select_only(obj)
        bpy.ops.object.convert(target="MESH")


def number_belongs_to(obj, prefix):
    if prefix == "D20":
        return obj.name.startswith("Face_")
    return obj.name.startswith(prefix + "_Face_")


def object_belongs_to_export(obj, prefix, body):
    if obj == body or number_belongs_to(obj, prefix):
        return True
    if not obj.name.startswith(prefix + "_"):
        return False
    return any(token in obj.name for token in (
        "Floating_Frost_Motes",
        "Moonwhite_Broken_Ice_Seam",
        "Floating_Ice_Shards",
        "Continuous_Frost_Gradient_v3",
        "Every_Face_Vertex_Frost_v6",
    ))


def export_die(output_dir, prefix, body):
    bpy.ops.object.select_all(action="DESELECT")
    selected = []
    for obj in bpy.context.scene.objects:
        if not object_belongs_to_export(obj, prefix, body):
            continue
        obj.hide_set(False)
        obj.hide_viewport = False
        obj.hide_render = False
        obj.select_set(True)
        selected.append(obj)
    if not selected:
        raise RuntimeError(f"No objects selected for {prefix}")
    bpy.context.view_layer.objects.active = body
    output_path = os.path.join(output_dir, EXPORTS[prefix])
    bpy.ops.export_scene.gltf(
        filepath=output_path,
        export_format="GLB",
        use_selection=True,
        export_materials="EXPORT",
        export_image_format="AUTO",
        export_apply=True,
        export_extras=True,
        export_cameras=False,
        export_lights=False,
    )
    print(f"EXPORTED {output_path} OBJECTS={len(selected)}", flush=True)


def main():
    output_dir = output_directory()
    os.makedirs(output_dir, exist_ok=True)
    scene = bpy.context.scene
    scene.render.engine = "CYCLES"
    scene.cycles.samples = 4
    scene.render.bake.use_clear = True
    scene.render.bake.use_pass_direct = False
    scene.render.bake.use_pass_indirect = False
    scene.render.bake.use_pass_color = True

    replace_gradient_materials()
    convert_export_curves_and_fonts()
    bodies = sorted((obj for obj in scene.objects if "_Body_" in obj.name), key=lambda obj: obj.name)
    if len(bodies) != 7:
        raise RuntimeError(f"Expected 7 dice bodies, found {len(bodies)}")
    for body in bodies:
        prefix = body.name.split("_Body_")[0]
        material = shell_material(f"Moonwhite_Web_Baked_{prefix}")
        bake_body(body, material)
    for body in bodies:
        prefix = body.name.split("_Body_")[0]
        export_die(output_dir, prefix, body)


main()
