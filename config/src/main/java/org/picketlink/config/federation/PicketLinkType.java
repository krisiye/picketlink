package org.picketlink.config.federation;

import org.picketlink.config.federation.handler.Handlers;
import org.picketlink.config.idm.IDMType;

/**
 * Overall consolidated config holder type
 *
 * @author anil saldhana
 */
public class PicketLinkType {
    private ProviderType idpOrSP;
    private Handlers handlers;
    private STSType stsType;
    private boolean enableAudit;
    private IDMType idmType;

    public ProviderType getIdpOrSP() {
        return idpOrSP;
    }

    public void setIdpOrSP(ProviderType idpOrSP) {
        this.idpOrSP = idpOrSP;
    }

    public Handlers getHandlers() {
        return handlers;
    }

    public void setHandlers(Handlers handlers) {
        this.handlers = handlers;
    }

    public STSType getStsType() {
        return stsType;
    }

    public void setStsType(STSType stsType) {
        this.stsType = stsType;
    }

    public boolean isEnableAudit() {
        return enableAudit;
    }

    public void setEnableAudit(boolean enableAudit) {
        this.enableAudit = enableAudit;
    }

    public IDMType getIdmType() {
        return idmType;
    }

    public void setIdmType(IDMType idmType) {
        this.idmType = idmType;
    }
}