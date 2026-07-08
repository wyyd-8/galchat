import os
import sys

import bpy


def command_arguments():
    separator = sys.argv.index("--")
    arguments = sys.argv[separator + 1:]
    if len(arguments) != 1:
        raise RuntimeError("Expected output GLB path after --")
    return os.path.abspath(arguments[0])


def select_only(obj):
    bpy.ops.object.select_all(action="DESELECT")
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


def image_size(obj):
    if "_Body_" in obj.name:
        return 1024
    if obj.name.startswith("Face_"):
        return 256
    return 512


def has_procedural_base_color(obj):
    if not obj.material_slots or obj.material_slots[0].material is None:
        return False
    material = obj.material_slots[0].material
    if not material.use_nodes:
        return False
    principled = next(
        (node for node in material.node_tree.nodes if node.type == "BSDF_PRINCIPLED"),
        None,
    )
    return principled is not None and principled.inputs["Base Color"].is_linked


def convert_bakeable_curves(scene):
    for obj in list(scene.objects):
        if obj.type not in {"CURVE", "FONT"} or not has_procedural_base_color(obj):
            continue
        select_only(obj)
        bpy.ops.object.convert(target="MESH")


def bake_object_base_color(obj):
    if obj.type != "MESH" or not obj.material_slots:
        return False

    source_material = obj.material_slots[0].material
    if source_material is None or not source_material.use_nodes:
        return False
    source_principled = next(
        (node for node in source_material.node_tree.nodes if node.type == "BSDF_PRINCIPLED"),
        None,
    )
    if source_principled is None or not source_principled.inputs["Base Color"].is_linked:
        return False

    ensure_uv_map(obj)
    material = source_material.copy()
    material.name = f"{source_material.name}_Baked_{obj.name}"
    obj.material_slots[0].material = material
    nodes = material.node_tree.nodes
    links = material.node_tree.links
    principled = next(node for node in nodes if node.type == "BSDF_PRINCIPLED")

    size = image_size(obj)
    image = bpy.data.images.new(
        name=f"{obj.name}_BaseColor",
        width=size,
        height=size,
        alpha=False,
    )
    image.colorspace_settings.name = "sRGB"
    texture = nodes.new("ShaderNodeTexImage")
    texture.name = "Baked Base Color"
    texture.image = image
    nodes.active = texture
    texture.select = True

    select_only(obj)
    bpy.ops.object.bake(type="DIFFUSE", pass_filter={"COLOR"}, margin=12)
    for link in list(principled.inputs["Base Color"].links):
        links.remove(link)
    links.new(texture.outputs["Color"], principled.inputs["Base Color"])
    image.pack()
    print(f"BAKED {obj.name} {size}x{size}", flush=True)
    return True


def main():
    output_path = command_arguments()
    scene = bpy.context.scene
    scene.render.engine = "CYCLES"
    scene.cycles.samples = 8
    scene.render.bake.use_clear = True
    scene.render.bake.use_pass_direct = False
    scene.render.bake.use_pass_indirect = False
    scene.render.bake.use_pass_color = True

    convert_bakeable_curves(scene)
    baked_count = sum(bake_object_base_color(obj) for obj in list(scene.objects))
    if baked_count == 0:
        raise RuntimeError("No procedural base-color materials were found")

    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    bpy.ops.export_scene.gltf(
        filepath=output_path,
        export_format="GLB",
        export_materials="EXPORT",
        export_image_format="AUTO",
        export_apply=True,
    )
    print(f"EXPORTED {output_path} WITH {baked_count} BAKED OBJECTS", flush=True)


main()
