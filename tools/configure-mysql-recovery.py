"""Disable optional Essentials update polling after exact restore, retaining any diagnostic boot."""
from pathlib import Path
import argparse
import json
import mysql_recovery as R


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--run',type=Path,required=True);p.add_argument('--diagnostic-boot',type=int)
    a=p.parse_args();run=a.run.resolve();manifest,last=R.stopped(run)
    R.check(run.parent==R.ROOT/'run' and manifest.get('recovery_acceptance') and not (run/'restore-incomplete.json').exists(),'Require completed isolated restore')
    source_boot=manifest['recovery_acceptance']['source_boot'];diagnostics=[]
    R.check(last['boot']==source_boot+(1 if a.diagnostic_boot else 0),'Unexpected recovery boot inventory before configuration')
    if a.diagnostic_boot:
        R.check(a.diagnostic_boot==last['boot'],'Diagnostic must be the first restored boot')
        path=run/f'evidence/boot{a.diagnostic_boot}.server.log';log=path.read_text()
        R.check('EssentialsX-2.22.0.jar//com.earth2me.essentials.updatecheck.UpdateChecker.' in log and 'zip file closed' in log and 'This plugin is not properly shutting down its async tasks' in log,'Missing specific Essentials update-checker diagnostic')
        diagnostics.append(dict(boot=a.diagnostic_boot,log=path.relative_to(run).as_posix(),sha256=R.M.sha(path),classification='PRECHECK_DIAGNOSTIC_NOT_ACCEPTED',reason='EssentialsX optional online UpdateChecker outlived plugin disable; original session and log retained'))
    file=run/'plugins/Essentials/config.yml';before=file.read_text();R.check(before.count('update-check: true\n')==1,'Unexpected Essentials update setting')
    after=before.replace('update-check: true\n','update-check: false\n');file.write_text(after)
    evidence=run/'evidence/recovery'
    (evidence/'essentials-before.yml').write_text(before);(evidence/'essentials-after.yml').write_text(after)
    R.write(evidence/'acceptance-plan.json',dict(source_boot=source_boot,first_boot=last['boot']+1,diagnostic_boots=diagnostics,
        config_change=dict(path='plugins/Essentials/config.yml',before_sha256=R.M.sha(evidence/'essentials-before.yml'),after_sha256=R.M.sha(file),reason='Disable optional network update polling before controlled stop/crash acceptance; no gameplay setting changed'),
        scenarios=['production-offline-recovery' if manifest.get('nation_production_acceptance',{}).get('mode')=='offline' else 'production-recovery' if manifest.get('nation_production_acceptance') else 'sale-recovery' if manifest.get('nation_trade_acceptance') else 'third']*3,
        modes=['normal-restored-replay','post-replay-process-interruption','normal-recovered-replay']))
    print(json.dumps(dict(status='RECOVERY_CONFIGURED',run=str(run),first_boot=last['boot']+1)))


if __name__=='__main__':main()
