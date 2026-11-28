// Planar previews need an explicit marker: older PWA histories could retain
// unused preview coordinates after the manual grid had changed.
export function hasPreviewGeometry(settings) {
 return !!settings.warp?.length || !!(settings.curved && settings.previewQuad && settings.previewGeometryVersion === 1);
}
